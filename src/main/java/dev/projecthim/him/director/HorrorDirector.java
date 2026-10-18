package dev.projecthim.him.director;

import dev.projecthim.him.memory.BehaviorProfile;
import dev.projecthim.him.memory.Personality;
import dev.projecthim.him.memory.Personality.Trait;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.util.MathUtil;
import dev.projecthim.him.util.Weighted;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Decides whether, what and how HIM does something to a player. Pure logic: no server access.
 *
 * <p>The decision is layered so that every outcome has a reason:
 * <ol>
 *     <li>Hazard: the chance to act grows with the time it has been quiet (Weibull hazard), scaled by
 *     stage, tension phase, personality and server health. Silence is the default.</li>
 *     <li>Global budget: a server wide token bucket caps total activity.</li>
 *     <li>Candidates: events whose hard requirements, cooldowns and intensity caps allow them.</li>
 *     <li>Intent: a utility score per intent from personality, the player's temperament and the situation.</li>
 *     <li>Event: weighted by fit, novelty for this player, and HIM's learned affinity.</li>
 *     <li>Mode and subversion: private, group or shared; and whether to break the learned pattern.</li>
 * </ol>
 */
public final class HorrorDirector {

    private final List<EventSpec> catalog;
    private DirectorSettings settings;

    public HorrorDirector(List<EventSpec> catalog, DirectorSettings settings) {
        this.catalog = List.copyOf(catalog);
        this.settings = settings;
    }

    public void updateSettings(DirectorSettings settings) {
        this.settings = settings;
    }

    public DirectorSettings settings() {
        return settings;
    }

    public List<EventSpec> catalog() {
        return catalog;
    }

    public Optional<EventSpec> spec(String id) {
        return catalog.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** Chance to act during this evaluation for a periodic trigger. Exposed for diagnostics and tests. */
    public double hazardProbability(DirectorInput in, Stage stage) {
        PlayerContext ctx = in.context();
        double quietSeconds = (in.now() - in.tension().quietSince()) / 1000.0;
        if (quietSeconds < settings.minGapSeconds()) {
            return 0;
        }
        Personality p = in.world().personality();
        double meanSeconds = settings.meanGapMinutes(stage) * 60.0
                * (0.75 + 0.5 * p.get(Trait.PATIENCE))
                / Math.max(0.05, settings.frequency());
        double k = settings.weibullShape();
        // Scale parameter so that the distribution mean equals meanSeconds: mean = lambda * Gamma(1 + 1/k).
        double lambda = meanSeconds / gammaOnePlusInverse(k);
        double t = Math.max(1.0, quietSeconds - settings.minGapSeconds() * 0.5);
        double hazard = (k / lambda) * Math.pow(t / lambda, k - 1);
        double situational = 1.0;
        if (ctx.night()) {
            situational *= 1.35;
        }
        if (ctx.underground() || ctx.dark()) {
            situational *= 1.2;
        }
        if (ctx.thundering()) {
            situational *= 1.25;
        }
        if (!ctx.alone()) {
            // Groups get less attention individually; the global budget handles the total.
            situational *= 0.8;
        }
        double rate = hazard * situational * in.tension().hazardMultiplier() * in.performance();
        return 1.0 - Math.exp(-rate * in.intervalSeconds());
    }

    public DirectorDecision decide(DirectorInput in, GlobalBudget budget, RandomGenerator random) {
        PlayerContext ctx = in.context();
        PlayerMemory memory = in.memory();
        if (in.performance() <= 0.0) {
            return new DirectorDecision.Hold("performance");
        }
        if (ctx.busy() && in.trigger() != Trigger.WAKE) {
            return new DirectorDecision.Hold("player-busy");
        }
        in.tension().update(in.now(), random);
        Stage stage = StageCalculator.stageOf(memory, settings);

        double chance;
        if (in.trigger() == Trigger.PERIODIC) {
            chance = hazardProbability(in, stage);
        } else {
            double quietSeconds = (in.now() - in.tension().quietSince()) / 1000.0;
            if (quietSeconds < settings.minGapSeconds() * 0.5) {
                return new DirectorDecision.Hold("min-gap");
            }
            chance = in.trigger().baseChance() * settings.frequency() * in.performance()
                    * in.tension().hazardMultiplier();
            if (in.trigger() == Trigger.JOIN && memory.pendingReturn() != null) {
                chance = Math.max(chance, 0.7);
            }
        }
        if (chance <= 0 || random.nextDouble() >= chance) {
            return new DirectorDecision.Hold("quiet");
        }

        double intensityCap = MathUtil.clamp(in.tension().intensityCap() * settings.intensity(), 0.1, 1.0);
        List<EventSpec> candidates = candidates(in, stage, intensityCap);
        if (candidates.isEmpty()) {
            return new DirectorDecision.Hold("no-candidate");
        }

        Map<Intent, List<EventSpec>> byIntent = new EnumMap<>(Intent.class);
        for (EventSpec s : candidates) {
            byIntent.computeIfAbsent(s.intent(), i -> new ArrayList<>()).add(s);
        }
        List<Intent> intents = new ArrayList<>(byIntent.keySet());
        Intent intent = Weighted.pick(intents, i -> intentUtility(i, in, stage), random).orElse(null);
        if (intent == null) {
            return new DirectorDecision.Hold("no-intent");
        }
        EventSpec spec = Weighted.pick(byIntent.get(intent), s -> eventWeight(s, in, stage), random).orElse(null);
        if (spec == null) {
            return new DirectorDecision.Hold("no-event");
        }

        double cost = GlobalBudget.cost(spec.intensity());
        if (!budget.tryConsume(cost, in.now())) {
            return new DirectorDecision.Hold("global-budget");
        }
        EncounterMode mode = chooseMode(spec, ctx, in.world().personality(), random);
        boolean subvert = spec.visual() && random.nextDouble() < memory.expectations()
                .subversionChance(in.world().personality().get(Trait.SUBVERSION), in.now(), settings.subversionGapMs());
        return new DirectorDecision.Act(spec, mode, subvert, cost);
    }

    /** Events that may run right now, ignoring randomness. */
    public List<EventSpec> candidates(DirectorInput in, Stage stage, double intensityCap) {
        PlayerContext ctx = in.context();
        List<EventSpec> out = new ArrayList<>();
        for (EventSpec s : catalog) {
            if (settings.disabledEvents().contains(s.id())
                    || !s.triggers().contains(in.trigger())
                    || stage.ordinal() < s.minStage().ordinal()
                    || s.intensity() > intensityCap + 1.0E-9
                    || (s.visual() && !in.apparitionAvailable())
                    || (s.intent() == Intent.HUNT && (!in.huntAvailable() || settings.aggression() <= 0))
                    || (s.modifiesWorld() && !in.worldEditsAllowed())
                    || !modeAvailable(s, ctx)) {
                continue;
            }
            long last = in.memory().lastEventAt(s.id());
            if (last > 0 && in.now() - last < s.cooldownMs() * settings.cooldownMultiplier()) {
                continue;
            }
            if (!s.requirement().test(ctx)) {
                continue;
            }
            out.add(s);
        }
        return out;
    }

    private static boolean modeAvailable(EventSpec s, PlayerContext ctx) {
        // Only GROUP needs other players; SHARED works with whoever is there, even alone.
        if (s.supports(EncounterMode.PRIVATE) || s.supports(EncounterMode.SHARED)) {
            return true;
        }
        return ctx.nearbyPlayers() > 0;
    }

    double intentUtility(Intent intent, DirectorInput in, Stage stage) {
        PlayerContext ctx = in.context();
        Personality p = in.world().personality();
        PlayerMemory m = in.memory();
        BehaviorProfile.Temperament temperament = m.behavior().temperament();
        double u = switch (intent) {
            case PRESENCE -> 0.5 + 0.5 * (1 - stage.normalized()) + (ctx.underground() ? 0.3 : 0) + (ctx.night() ? 0.1 : 0);
            case OBSERVE -> 0.35 + p.get(Trait.CURIOSITY) * 0.8 + (ctx.exposedAtNight() ? 0.25 : 0);
            case STALK -> 0.15 + p.get(Trait.PATIENCE) * 0.5 + p.get(Trait.ISOLATION) * (ctx.alone() ? 0.6 : 0.1)
                    + m.obsession() * 0.5;
            case INTERFERE -> 0.2 + p.get(Trait.MIMICRY) * 0.4 + (ctx.atBase(48) ? 0.5 : 0) + p.get(Trait.BOLDNESS) * 0.2;
            case HUNT -> (0.3 + p.get(Trait.AGGRESSION)) * settings.aggression()
                    * (ctx.night() || ctx.underground() ? 1.3 : 0.6)
                    * (ctx.alone() ? 1.0 : 0.55)
                    * (0.4 + in.tension().tension() * 1.6)
                    * (0.7 + m.obsession())
                    * (stage == Stage.OBSESSED ? 1.4 : 1.0)
                    * (ctx.health() > 0.6 ? 1.0 : 0.15);
        };
        // Adapt to how this player reacts.
        u *= switch (temperament) {
            case OBLIVIOUS -> intent == Intent.PRESENCE || intent == Intent.INTERFERE ? 1.4 : 1.0;
            case SKITTISH -> intent == Intent.STALK ? 1.5 : intent == Intent.HUNT ? 1.2 : 1.0;
            case AGGRESSIVE -> intent == Intent.OBSERVE ? 0.8 : intent == Intent.STALK ? 1.3 : 1.0;
            case CURIOUS -> intent == Intent.OBSERVE ? 1.2 : intent == Intent.HUNT ? 1.15 : 1.0;
            case UNKNOWN -> 1.0;
        };
        return Math.max(0.01, u);
    }

    double eventWeight(EventSpec s, DirectorInput in, Stage stage) {
        double fit = s.fit().applyAsDouble(in.context());
        double novelty = in.memory().expectations().novelty(s.id(), s.intent().name());
        double affinity = in.world().affinity(s.id());
        double stageFactor = 1.0 + 0.1 * (stage.ordinal() - s.minStage().ordinal());
        // Towards the end of a build-up the stronger events become the natural choice: a climax.
        double climax = in.tension().phase() == TensionTracker.Phase.BUILD_UP
                ? 1.0 + 2.0 * in.tension().tension() * s.intensity() : 1.0;
        return s.weight() * Math.max(0, fit) * novelty * affinity * stageFactor * climax;
    }

    private static EncounterMode chooseMode(EventSpec s, PlayerContext ctx, Personality p, RandomGenerator random) {
        if (ctx.alone()) {
            return s.supports(EncounterMode.PRIVATE) ? EncounterMode.PRIVATE
                    : s.modes().iterator().next();
        }
        List<EncounterMode> modes = new ArrayList<>(s.modes());
        return Weighted.pick(modes, m -> switch (m) {
            case PRIVATE -> 0.4 + p.get(Trait.ISOLATION);
            case GROUP -> 0.7;
            case SHARED -> 0.3 + p.get(Trait.BOLDNESS) * 0.5;
        }, random).orElse(EncounterMode.PRIVATE);
    }

    /** Gamma(1 + 1/k) via the Lanczos approximation, adequate for k in [1, 5]. */
    static double gammaOnePlusInverse(double k) {
        return gamma(1.0 + 1.0 / k);
    }

    private static double gamma(double x) {
        double[] g = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
                -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
                1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.PI / (Math.sin(Math.PI * x) * gamma(1 - x));
        }
        x -= 1;
        double a = g[0];
        double t = x + 7.5;
        for (int i = 1; i < 9; i++) {
            a += g[i] / (x + i);
        }
        return Math.sqrt(2 * Math.PI) * Math.pow(t, x + 0.5) * Math.exp(-t) * a;
    }
}
