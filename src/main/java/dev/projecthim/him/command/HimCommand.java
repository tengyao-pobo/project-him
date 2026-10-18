package dev.projecthim.him.command;

import dev.projecthim.him.Him;
import dev.projecthim.him.HimPlugin;
import dev.projecthim.him.director.DirectorInput;
import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.PlayerContext;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.director.StageCalculator;
import dev.projecthim.him.director.Trigger;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.EventManager;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.BehaviorProfile;
import dev.projecthim.him.memory.EncounterRecord;
import dev.projecthim.him.memory.HeatMap;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.memory.Personality;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.player.PlayerSession;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /him} administration command. Every subcommand checks its own permission. */
public final class HimCommand implements BasicCommand {

    private record Sub(String name, String permission, String usage, String help) {
    }

    private static final List<Sub> SUBS = List.of(
            new Sub("status", "him.command.status", "/him status", "狀態、效能、進行中的事件"),
            new Sub("events", "him.command.status", "/him events", "列出所有恐怖事件"),
            new Sub("personality", "him.command.status", "/him personality", "HIM 目前的性格"),
            new Sub("debug", "him.command.debug", "/him debug [on|off|<玩家>]", "即時除錯訊息 / 玩家導演狀態"),
            new Sub("trigger", "him.command.trigger", "/him trigger <事件> <玩家> [private|group|shared] [subvert]", "立即觸發事件（測試用，不影響記憶）"),
            new Sub("chase", "him.command.trigger", "/him chase <玩家> [now]", "開始追殺（now = 直接現身）"),
            new Sub("summon", "him.command.summon", "/him summon <玩家> [shared]", "讓 HIM 出現在玩家附近（最多 60 秒）"),
            new Sub("stop", "him.command.summon", "/him stop [玩家]", "結束事件並移除所有 HIM"),
            new Sub("memory", "him.command.memory", "/him memory <玩家> [reset]", "查看 / 重置玩家記憶"),
            new Sub("stage", "him.command.memory", "/him stage <玩家> <0-4|auto>", "設定玩家階段"),
            new Sub("participation", "him.command.memory", "/him participation <玩家> <on|off|default>", "設定玩家是否參與"),
            new Sub("reset", "him.command.reset", "/him reset world confirm", "重置 HIM 的世界記憶與性格"),
            new Sub("pause", "him.command.toggle", "/him pause", "暫停 HIM（結束所有事件）"),
            new Sub("resume", "him.command.toggle", "/him resume", "恢復 HIM"),
            new Sub("cleanup", "him.command.summon", "/him cleanup", "清除殘留實體、假方塊並排程還原世界修改"),
            new Sub("reload", "him.command.reload", "/him reload", "重新載入設定檔"),
            new Sub("selftest", "him.command.selftest", "/him selftest", "執行伺服器內自我測試"));

    private final HimPlugin plugin;

    public HimCommand(HimPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String permission() {
        return null;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return SUBS.stream().anyMatch(s -> sender.hasPermission(s.permission()));
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Him him = plugin.him();
        if (him == null) {
            error(sender, "HIM 尚未啟動（請查看主控台的設定錯誤）。可以修正後使用 /him reload。");
            if (args.length > 0 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("him.command.reload")) {
                plugin.reloadHim(sender);
            }
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return;
        }
        Sub sub = SUBS.stream().filter(s -> s.name().equalsIgnoreCase(args[0])).findFirst().orElse(null);
        if (sub == null) {
            error(sender, "未知的子指令「" + args[0] + "」。");
            help(sender);
            return;
        }
        if (!sender.hasPermission(sub.permission())) {
            error(sender, "你沒有權限（" + sub.permission() + "）。");
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub.name()) {
            case "status" -> status(sender, him);
            case "events" -> events(sender, him);
            case "personality" -> personality(sender, him);
            case "debug" -> debug(sender, him, rest);
            case "trigger" -> trigger(sender, him, rest);
            case "chase" -> chase(sender, him, rest);
            case "summon" -> summon(sender, him, rest);
            case "stop" -> stop(sender, him, rest);
            case "memory" -> memory(sender, him, rest);
            case "stage" -> stage(sender, him, rest);
            case "participation" -> participation(sender, him, rest);
            case "reset" -> reset(sender, him, rest);
            case "pause" -> {
                him.setPaused(true);
                ok(sender, "HIM 已暫停，所有事件已結束。");
            }
            case "resume" -> {
                him.setPaused(false);
                ok(sender, "HIM 已恢復。");
            }
            case "cleanup" -> cleanup(sender, him);
            case "reload" -> plugin.reloadHim(sender);
            case "selftest" -> new SelfTest(him, sender).start();
            default -> help(sender);
        }
    }

    private static void help(CommandSender sender) {
        sender.sendMessage(Component.text("── HIM — He Is Memory ──", NamedTextColor.DARK_RED));
        for (Sub s : SUBS) {
            if (sender.hasPermission(s.permission())) {
                sender.sendMessage(Component.text(s.usage(), NamedTextColor.GRAY)
                        .append(Component.text("  " + s.help(), NamedTextColor.DARK_GRAY)));
            }
        }
    }

    // ------------------------------------------------------------------ status

    private static void status(CommandSender s, Him him) {
        var perf = him.performance();
        line(s, "狀態", (him.config().enabled() ? "啟用" : "停用（設定檔）") + (him.paused() ? "，已暫停" : ""));
        line(s, "效能", perf.level() + String.format(Locale.ROOT, "  伺服器 %.1f mspt / %.1f TPS，HIM %.3f ms/tick",
                perf.serverMspt(), perf.serverTps(), perf.ownMs()));
        line(s, "玩家", him.sessions().size() + " 個工作階段");
        line(s, "實體", him.entities().activeBodies() + " 個身影 / " + him.entities().activeCarriers() + " 個載體（上限 "
                + him.config().apparitions().maxActive() + "）");
        line(s, "全域預算", String.format(Locale.ROOT, "%.2f / %.1f", him.director().budget().tokens(him.clock().now()),
                him.director().budget().capacity()));
        line(s, "假方塊", String.valueOf(him.fakes().count()));
        line(s, "待還原世界修改", String.valueOf(him.journal().pending()));
        line(s, "累計遭遇 / 追殺", him.memory().world().totalEncounters() + " / " + him.memory().world().totalHunts());
        line(s, "最近決策", him.director().lastDecision());
        List<EventInstance> active = him.events().active();
        line(s, "進行中事件", active.isEmpty() ? "無" : "");
        for (EventInstance i : active) {
            s.sendMessage(Component.text("  • " + i.context().instanceId() + " → " + i.context().target().getName()
                    + " (" + i.context().mode() + ", " + (him.tick() - i.startTick()) / 20 + "s)", NamedTextColor.GRAY));
        }
    }

    private static void events(CommandSender s, Him him) {
        var disabled = him.config().director().disabledEvents();
        for (HorrorEvent e : him.events().all()) {
            EventSpec sp = e.spec();
            boolean manual = sp.triggers().contains(Trigger.MANUAL);
            NamedTextColor color = disabled.contains(sp.id()) ? NamedTextColor.DARK_GRAY : manual ? NamedTextColor.AQUA
                    : NamedTextColor.GRAY;
            s.sendMessage(Component.text(String.format(Locale.ROOT, "%-18s %-9s 階段≥%d 強度%.2f 冷卻%3d分 %s%s%s",
                    sp.id(), sp.intent(), sp.minStage().ordinal(), sp.intensity(), sp.cooldownMs() / 60_000,
                    sp.modes(), sp.modifiesWorld() ? " [真實修改]" : "", disabled.contains(sp.id()) ? " [停用]"
                            : manual ? " [僅手動]" : ""), color));
        }
    }

    private static void personality(CommandSender s, Him him) {
        Personality p = him.memory().world().personality();
        for (Personality.Trait t : Personality.Trait.values()) {
            line(s, t.name().toLowerCase(Locale.ROOT), String.format(Locale.ROOT, "%.3f（基準 %.3f）", p.get(t), p.baseline(t)));
        }
    }

    // ------------------------------------------------------------------ debug

    private static void debug(CommandSender s, Him him, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("on") || a[0].equalsIgnoreCase("off")) {
            if (!(s instanceof Player p)) {
                error(s, "主控台請改用 config.yml 的 logging.level: debug。");
                return;
            }
            boolean on = a.length == 0 ? !him.log().isWatching(p.getUniqueId()) : a[0].equalsIgnoreCase("on");
            him.log().toggleWatcher(p.getUniqueId(), on);
            ok(s, on ? "已開啟除錯訊息。" : "已關閉除錯訊息。");
            return;
        }
        Player target = online(s, a[0]);
        if (target == null) {
            return;
        }
        PlayerSession session = him.sessions().get(target.getUniqueId());
        if (session == null) {
            error(s, "這位玩家的記憶尚未載入。");
            return;
        }
        PlayerContext c = session.context();
        Stage stage = StageCalculator.stageOf(session.memory(), him.config().director());
        session.tension().update(him.clock().now(), him.random());
        line(s, "符合資格", String.valueOf(him.sessions().eligible(target)));
        line(s, "階段", stage + "（" + stage.ordinal() + "）");
        line(s, "張力", String.format(Locale.ROOT, "%.2f  階段 %s  強度上限 %.2f", session.tension().tension(),
                session.tension().phase(), session.tension().intensityCap()));
        line(s, "事件鎖定", him.events().isLocked(target.getUniqueId()) ? him.events().instanceOf(target.getUniqueId())
                .context().instanceId() : "無");
        if (c == null) {
            line(s, "情境", "尚未取樣");
            return;
        }
        line(s, "情境", (c.night() ? "夜晚 " : "白天 ") + (c.underground() ? "地下 " : "") + (c.sheltered() ? "室內 " : "")
                + (c.skyVisible() ? "露天 " : "") + "光照" + c.light() + " 附近玩家" + c.nearbyPlayers()
                + String.format(Locale.ROOT, " 速度%.1f", c.speed()) + (c.inCombat() ? " 戰鬥中" : "")
                + (c.baseKnown() ? String.format(Locale.ROOT, " 距基地%.0f", c.distanceToBase()) : " 基地未知"));
        DirectorInput input = new DirectorInput(c, session.memory(), him.memory().world(), session.tension(),
                Trigger.PERIODIC, him.clock().now(), him.performance().directorMultiplier(), him.canSpawnApparition(),
                him.huntSlotFree(), him.protection().realChangesAllowed(target.getWorld()),
                him.config().director().evaluationSeconds());
        double p = him.director().director().hazardProbability(input, stage);
        line(s, "本次評估行動機率", String.format(Locale.ROOT, "%.4f（每 %.0f 秒評估一次）", p,
                him.config().director().evaluationSeconds()));
        List<EventSpec> candidates = him.director().director().candidates(input, stage,
                Math.min(1, session.tension().intensityCap() * him.config().director().intensity()));
        line(s, "可用事件", candidates.isEmpty() ? "無" : String.join(", ", candidates.stream().map(EventSpec::id).toList()));
    }

    // ------------------------------------------------------------------ actions

    private static void trigger(CommandSender s, Him him, String[] a) {
        if (a.length < 2) {
            error(s, "用法：/him trigger <事件> <玩家> [private|group|shared] [subvert]");
            return;
        }
        if (him.events().get(a[0].toLowerCase(Locale.ROOT)).isEmpty()) {
            error(s, "未知的事件「" + a[0] + "」。用 /him events 查看。");
            return;
        }
        Player target = online(s, a[1]);
        if (target == null) {
            return;
        }
        EncounterMode mode = EncounterMode.PRIVATE;
        boolean subvert = false;
        for (int i = 2; i < a.length; i++) {
            switch (a[i].toLowerCase(Locale.ROOT)) {
                case "private" -> mode = EncounterMode.PRIVATE;
                case "group" -> mode = EncounterMode.GROUP;
                case "shared" -> mode = EncounterMode.SHARED;
                case "subvert" -> subvert = true;
                default -> {
                    error(s, "未知的選項「" + a[i] + "」。");
                    return;
                }
            }
        }
        launch(s, him, target, a[0].toLowerCase(Locale.ROOT), mode, subvert);
    }

    private static void chase(CommandSender s, Him him, String[] a) {
        if (a.length < 1) {
            error(s, "用法：/him chase <玩家> [now]");
            return;
        }
        Player target = online(s, a[0]);
        if (target != null) {
            launch(s, him, target, "hunt", EncounterMode.PRIVATE, a.length > 1 && a[1].equalsIgnoreCase("now"));
        }
    }

    private static void summon(CommandSender s, Him him, String[] a) {
        if (a.length < 1) {
            error(s, "用法：/him summon <玩家> [shared]");
            return;
        }
        Player target = online(s, a[0]);
        if (target != null) {
            launch(s, him, target, "summon",
                    a.length > 1 && a[1].equalsIgnoreCase("shared") ? EncounterMode.SHARED : EncounterMode.PRIVATE, false);
        }
    }

    private static void launch(CommandSender s, Him him, Player target, String id, EncounterMode mode, boolean subvert) {
        if (him.events().isLocked(target.getUniqueId())) {
            error(s, target.getName() + " 正在經歷另一個事件；請先用 /him stop " + target.getName() + "。");
            return;
        }
        if (him.sessions().get(target.getUniqueId()) == null) {
            error(s, target.getName() + " 的記憶尚未載入，請稍候再試。");
            return;
        }
        EventManager.LaunchResult r = him.director().launch(target, id, mode, subvert, Trigger.MANUAL);
        switch (r) {
            case STARTED -> ok(s, "已對 " + target.getName() + " 觸發「" + id + "」（" + mode + (subvert ? "，打破規律" : "") + "）。");
            case NOT_APPLICABLE -> error(s, "目前情境不適用此事件（例如找不到門、火把、樹，或缺少其他玩家）。");
            case NO_SPOT -> error(s, "找不到合適的位置（地形、視線或已達身影上限）。換個地方再試。");
            case PARTICIPANT_BUSY -> error(s, "參與者正在經歷其他事件。");
            case UNKNOWN_EVENT -> error(s, "未知的事件。");
            case ERROR -> error(s, "事件發生錯誤，詳情請見主控台。");
        }
    }

    private static void stop(CommandSender s, Him him, String[] a) {
        if (a.length >= 1) {
            Player target = online(s, a[0]);
            if (target != null) {
                int n = him.events().cancelFor(target.getUniqueId());
                ok(s, "已結束 " + n + " 個與 " + target.getName() + " 有關的事件。");
            }
            return;
        }
        him.events().cancelAll(Outcome.INTERRUPTED);
        him.entities().removeAll();
        him.fakes().revertAll();
        ok(s, "所有事件已結束，所有 HIM 已移除，假方塊已還原。");
    }

    private static void cleanup(CommandSender s, Him him) {
        int strays = him.entities().sweepAllWorlds();
        him.entities().pruneInvalid();
        him.fakes().revertAll();
        him.journal().expediteAll();
        ok(s, "已移除 " + strays + " 個殘留實體、還原假方塊；" + him.journal().pending()
                + " 個世界修改將在附近無人時還原。");
    }

    // ------------------------------------------------------------------ memory

    private static void memory(CommandSender s, Him him, String[] a) {
        if (a.length < 1) {
            error(s, "用法：/him memory <玩家> [reset]");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (target == null) {
            error(s, "找不到玩家「" + a[0] + "」。");
            return;
        }
        if (a.length > 1 && a[1].equalsIgnoreCase("reset")) {
            if (!s.hasPermission("him.command.reset")) {
                error(s, "你沒有權限（him.command.reset）。");
                return;
            }
            Player online = target.getPlayer();
            if (online != null) {
                him.events().cancelFor(online.getUniqueId());
                him.sessions().quitSilently(online);
            }
            him.memory().delete(target.getUniqueId());
            him.memory().world().removeLandmarksOf(target.getUniqueId());
            him.memory().markWorldDirty();
            if (online != null) {
                him.sessions().join(online);
            }
            ok(s, "已清除 HIM 對 " + a[0] + " 的所有記憶。");
            return;
        }
        him.memory().peek(target.getUniqueId()).whenComplete((opt, err) -> {
            if (err != null) {
                error(s, "讀取記憶失敗：" + err.getMessage());
            } else if (opt.isEmpty()) {
                error(s, "HIM 對 " + a[0] + " 沒有任何記憶。");
            } else {
                showMemory(s, him, opt.get());
            }
        });
    }

    private static void showMemory(CommandSender s, Him him, PlayerMemory m) {
        long now = him.clock().now();
        Stage stage = StageCalculator.stageOf(m, him.config().director());
        BehaviorProfile b = m.behavior();
        s.sendMessage(Component.text("── HIM 對 " + m.lastName() + " 的記憶 ──", NamedTextColor.DARK_RED));
        line(s, "階段", stage + (m.stageOverride() >= 0 ? "（管理員固定）" : ""));
        line(s, "遊玩時間", formatDuration(Duration.ofSeconds(m.playSeconds())) + "，登入 " + m.sessions() + " 次");
        line(s, "執念", String.format(Locale.ROOT, "%.2f", m.obsession()));
        line(s, "參與", m.participating() == null ? "預設" : m.participating() ? "是" : "否");
        line(s, "性情", b.temperament() + String.format(Locale.ROOT, "（目擊 %d，注意到 %d，靠近 %d，逃跑 %d，攻擊 %d）",
                b.sightings(), b.noticed(), b.approached(), b.fled(), b.attacked()));
        line(s, "習慣", String.format(Locale.ROOT, "地下 %.0f%%，夜間戶外 %.0f%%，獨處 %.0f%%，建造 %.1f/分，挖掘 %.1f/分",
                b.undergroundRatio() * 100, b.nightOutdoorRatio() * 100, b.aloneRatio() * 100, b.buildsPerMinute(),
                b.minesPerMinute()));
        var chase = m.chase();
        line(s, "追殺", chase.hunts() + " 次：逃脫 " + chase.escapes() + "，被抓 " + chase.caught() + "，擊退 "
                + chase.repelled() + (chase.favouriteEscape() != null ? "，慣用逃法 " + chase.favouriteEscape() : ""));
        var d = m.dialogue();
        line(s, "告示牌", String.format(Locale.ROOT, "讀過 %d 塊，挑釁 %d，請求 %d（答應 %d），態度 %+.2f，待回覆 %d%s",
                d.signsRead(), d.provocations(), d.pleas(), d.pleasHonoured(), d.disposition(), d.pending().size(),
                now < d.quietUntil() ? "，平靜中（剩 " + formatDuration(Duration.ofMillis(d.quietUntil() - now)) + "）" : ""));
        Player online = Bukkit.getPlayer(m.id());
        String world = online != null ? online.getWorld().getName() : null;
        if (world != null) {
            HeatMap.BaseEstimate base = m.heat().estimateBase(world, now, him.config().memory().heatHalfLifeMs()).orElse(null);
            line(s, "基地推測", base == null ? "未知" : String.format(Locale.ROOT, "%.0f, %.0f, %.0f（信心 %.0f%%）",
                    base.x(), base.y(), base.z(), base.confidence() * 100));
            List<HeatMap.Cell> hot = m.heat().hottest(world, 3, now, him.config().memory().heatHalfLifeMs());
            List<String> cells = new ArrayList<>();
            for (HeatMap.Cell c : hot) {
                cells.add(String.format(Locale.ROOT, "(%.0f,%.0f) %.0f分", c.centroidX(), c.centroidZ(), c.dwellSeconds() / 60));
            }
            line(s, "活動熱點", cells.isEmpty() ? "無" : String.join("  ", cells));
        }
        line(s, "最近遭遇", "");
        for (EncounterRecord r : m.recentEncounters(6)) {
            s.sendMessage(Component.text(String.format(Locale.ROOT, "  %s 前  %-16s %-8s %s (%d,%d,%d)",
                    formatDuration(Duration.ofMillis(now - r.time())), r.eventId(), r.mode(), r.outcome(), r.x(), r.y(),
                    r.z()), NamedTextColor.GRAY));
        }
    }

    private static void stage(CommandSender s, Him him, String[] a) {
        if (a.length < 2) {
            error(s, "用法：/him stage <玩家> <0-4|auto>");
            return;
        }
        Player target = online(s, a[0]);
        if (target == null) {
            return;
        }
        PlayerSession session = him.sessions().get(target.getUniqueId());
        if (session == null) {
            error(s, "這位玩家的記憶尚未載入。");
            return;
        }
        int value;
        if (a[1].equalsIgnoreCase("auto")) {
            value = -1;
        } else {
            try {
                value = Integer.parseInt(a[1]);
            } catch (NumberFormatException e) {
                value = -2;
            }
            if (value < 0 || value > 4) {
                error(s, "階段必須是 0 到 4 或 auto。");
                return;
            }
        }
        session.memory().setStageOverride(value);
        him.memory().markDirty(target.getUniqueId());
        ok(s, target.getName() + " 的階段：" + (value < 0 ? "自動" : Stage.of(value).name()));
    }

    private static void participation(CommandSender s, Him him, String[] a) {
        if (a.length < 2) {
            error(s, "用法：/him participation <玩家> <on|off|default>");
            return;
        }
        Player target = online(s, a[0]);
        if (target == null) {
            return;
        }
        PlayerSession session = him.sessions().get(target.getUniqueId());
        if (session == null) {
            error(s, "這位玩家的記憶尚未載入。");
            return;
        }
        Boolean value = switch (a[1].toLowerCase(Locale.ROOT)) {
            case "on" -> Boolean.TRUE;
            case "off" -> Boolean.FALSE;
            default -> null;
        };
        session.memory().setParticipating(value);
        him.memory().markDirty(target.getUniqueId());
        if (Boolean.FALSE.equals(value)) {
            him.events().cancelFor(target.getUniqueId());
        }
        ok(s, target.getName() + " 的參與狀態：" + (value == null ? "預設" : value ? "參與" : "不參與"));
    }

    private static void reset(CommandSender s, Him him, String[] a) {
        if (a.length < 2 || !a[0].equalsIgnoreCase("world") || !a[1].equalsIgnoreCase("confirm")) {
            error(s, "這會讓 HIM 忘記一切並產生新的性格。確定請輸入：/him reset world confirm");
            return;
        }
        him.events().cancelAll(Outcome.INTERRUPTED);
        him.memory().resetWorld();
        ok(s, "HIM 已經重生，擁有全新的性格。玩家個別記憶不受影響（可用 /him memory <玩家> reset）。");
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Him him = plugin.him();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> sender.hasPermission(s.permission())).map(Sub::name)
                    .filter(n -> n.startsWith(prefix)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = switch (sub) {
            case "trigger" -> args.length == 2 && him != null
                    ? him.events().all().stream().map(HorrorEvent::id).toList()
                    : args.length == 3 ? players() : List.of("private", "group", "shared", "subvert");
            case "chase" -> args.length == 2 ? players() : List.of("now");
            case "summon" -> args.length == 2 ? players() : List.of("shared");
            case "stop", "memory", "debug" -> args.length == 2
                    ? Stream.concat(players().stream(), sub.equals("debug") ? Stream.of("on", "off") : Stream.<String>empty()).toList()
                    : sub.equals("memory") ? List.of("reset") : List.of();
            case "stage" -> args.length == 2 ? players() : List.of("0", "1", "2", "3", "4", "auto");
            case "participation" -> args.length == 2 ? players() : List.of("on", "off", "default");
            case "reset" -> args.length == 2 ? List.of("world") : List.of("confirm");
            default -> List.of();
        };
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    private static List<String> players() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    // ------------------------------------------------------------------ helpers

    private static Player online(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            error(s, "玩家「" + name + "」不在線上。");
        }
        return p;
    }

    static void ok(CommandSender s, String text) {
        s.sendMessage(Component.text("[HIM] ", NamedTextColor.DARK_RED).append(Component.text(text, NamedTextColor.GRAY)));
    }

    static void error(CommandSender s, String text) {
        s.sendMessage(Component.text("[HIM] ", NamedTextColor.DARK_RED).append(Component.text(text, NamedTextColor.RED)));
    }

    static void line(CommandSender s, String key, String value) {
        s.sendMessage(Component.text(key + "：", NamedTextColor.DARK_GRAY).append(Component.text(value, NamedTextColor.GRAY)));
    }

    private static String formatDuration(Duration d) {
        long minutes = d.toMinutes();
        if (minutes < 1) {
            return d.toSeconds() + " 秒";
        }
        if (minutes < 120) {
            return minutes + " 分";
        }
        if (minutes < 48 * 60) {
            return d.toHours() + " 小時";
        }
        return d.toDays() + " 天";
    }
}
