package dev.projecthim.him.director;

/** Result of one director evaluation. */
public sealed interface DirectorDecision {

    /** HIM chooses to stay quiet. The reason is for debugging only. */
    record Hold(String reason) implements DirectorDecision {
    }

    /** HIM acts. */
    record Act(EventSpec spec, EncounterMode mode, boolean subvert, double budgetCost) implements DirectorDecision {
    }
}
