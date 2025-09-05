package second_in_command

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.BattleAPI
import com.fs.starfarer.api.campaign.CampaignEventListener
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.listeners.FleetEventListener
import com.fs.starfarer.api.impl.SharedUnlockData
import com.fs.starfarer.api.loading.VariantSource
import com.fs.starfarer.api.util.Misc
import second_in_command.misc.NPCOfficerGenerator
import second_in_command.misc.SCSettings
import second_in_command.misc.backgrounds.AssociatesBackground
import second_in_command.misc.baseOrModSpec
import second_in_command.misc.codex.CodexHandler
import second_in_command.misc.logger
import second_in_command.skills.PlayerLevelEffects
import second_in_command.specs.SCBaseSkillPlugin
import second_in_command.specs.SCOfficer
import java.lang.Exception

//Per Fleet Data
class SCData(var fleet: CampaignFleetAPI) : EveryFrameScript, FleetEventListener {

    var isNPC = false
    var isPlayer = false
    var faction = fleet.faction
    var commander = fleet.commander

    private var officers = ArrayList<SCOfficer>()
    private var activeOfficers = MutableList<SCOfficer?>(3 + SCSettings.additionalSlots) { null }

    init {
        removeExtraOfficers()
    }

    fun init() {
        fleet.addScript(this)

        if (commander == null) {
            commander = faction.createRandomPerson()
        }

        officers.clear()

        isNPC = fleet != Global.getSector().playerFleet
        isPlayer = !isNPC

        if (!isNPC) {
            // Player fleet initialization
        }
        else {
            clearCommanderSkills()

            if (SCSettings.canNPCsSpawnWithSkills && !fleet.hasTag("sc_do_not_generate_skills")) {
                generateNPCOfficers()
            }
        }

        applyControllerHullmod()
    }

    fun getActiveOfficers() = activeOfficers.filterNotNull()

    fun removeExtraOfficers() {
        val maxSlots = 3 + SCSettings.additionalSlots
        if (activeOfficers.size > maxSlots) {
            activeOfficers = activeOfficers.subList(0, maxSlots).toMutableList()
        }
    }

    fun generateNPCOfficers() {
        NPCOfficerGenerator.generateForFleet(this, fleet)
    }

    fun getOfficersInFleet() : ArrayList<SCOfficer> {
        return ArrayList(officers)
    }

    fun addOfficerToFleet(officer: SCOfficer) {
        officer.data = this
        officers.add(officer)

        if (this.isPlayer) {
            CodexHandler.reportPlayerAwareOfThing(officer.aptitudeId, CodexHandler.APTITUDE_SET,
                CodexHandler.getAptitudEntryId(officer.aptitudeId), true)
        }
    }

    fun removeOfficerFromFleet(officer: SCOfficer) {
        if (officer.isAssigned()) {
            setOfficerInSlot(getOfficersAssignedSlot(officer)!!, null)
        }

        officer.data = null
        officers.remove(officer)
    }

    fun getOfficerInSlot(slotIndex: Int) : SCOfficer? {
        return activeOfficers.getOrNull(slotIndex)
    }

    fun setOfficerInSlot(slotIndex: Int, officer: SCOfficer?) {
        if (slotIndex < 0 || slotIndex >= activeOfficers.size) return
        var officerInSlot = getOfficerInSlot(slotIndex)
        activeOfficers[slotIndex] = officer

        if (officerInSlot != null) {
            var skills = officerInSlot.getActiveSkillPlugins()
            for (skill in skills) {
                skill.onDeactivation(this)
            }
            if (fleet.fleetData != null) fleet.fleetData.membersListCopy.forEach { it.updateStats() }
        }

        if (officer != null) {
            var skills = officer.getActiveSkillPlugins()
            for (skill in skills) {
                skill.onActivation(this)
            }
            if (fleet.fleetData != null) fleet.fleetData.membersListCopy.forEach { it.updateStats() }
        }
    }

    fun hasAptitudeInFleet(aptitudeId: String) : Boolean {
        return getOfficersInFleet().any { it.aptitudeId == aptitudeId }
    }

    fun setOfficerInEmptySlotIfAvailable(officer: SCOfficer) {
        // Пропускаем проверку категорий, если ограничения отключены
        if (!SCSettings.aptitudeCategoryRestriction) {
            var categories = officer.getAptitudePlugin().categories
            for (other in getActiveOfficers()) {
                if (other.aptitudeId == officer.aptitudeId) return
                var otherCategories = other.getAptitudePlugin().categories
                if (categories.any { otherCategories.contains(it) }) {
                    return
                }
            }
        }

        val isProgressionMode = SCSettings.progressionMode
        val level = Global.getSector().playerPerson.stats.level

        for (i in 0 until activeOfficers.size) {
            if (getOfficerInSlot(i) == null) { // Проверяем на null (пустой слот)
                val unlocked = when (i) {
                    0 -> !isProgressionMode || level >= SCSettings.progressionSlot1Level!!
                    1 -> !isProgressionMode || level >= SCSettings.progressionSlot2Level!!
                    2 -> !isProgressionMode || level >= SCSettings.progressionSlot3Level!!
                    else -> !isProgressionMode || level >= SCSettings.progressionSlot4Level!! // For slots 3+
                }

                if (unlocked) {
                    setOfficerInSlot(i, officer)
                    return
                }
            }
        }
    }

    fun getAssignedOfficers() : ArrayList<SCOfficer?> {
        return ArrayList(activeOfficers)
    }

    fun getAllActiveSkillsPlugins() : List<SCBaseSkillPlugin> {
        return getAssignedOfficers().filter { it != null }.flatMap { it!!.getActiveSkillPlugins() }
    }

    fun isSkillActive(skillId: String) : Boolean {
        return getAssignedOfficers().filter { it != null }
            .flatMap { it!!.getActiveSkillPlugins().map { it.getId() } }
            .contains(skillId)
    }

    fun getOfficersAssignedSlot(officer: SCOfficer) : Int? {
        if (!officer.isAssigned()) return null
        return activeOfficers.indexOfFirst { it == officer }.takeIf { it != -1 }
    }

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = true

    override fun advance(amount: Float) {
        if (!fleet.eventListeners.contains(this) && !fleet.isDespawning) {
            fleet.addEventListener(this)
        }

        for (skill in getAllActiveSkillsPlugins()) {
            skill.advance(this, amount)
        }

        if (isPlayer) {
            PlayerLevelEffects.advance(this, amount)
        }
    }

    override fun reportFleetDespawnedToListener(fleet: CampaignFleetAPI?,
                                                reason: CampaignEventListener.FleetDespawnReason?, param: Any?) {
        if (this.fleet == fleet) {
            var skills = getAllActiveSkillsPlugins()
            for (skill in skills) {
                skill.onDeactivation(this)
            }
        }
    }

    override fun reportBattleOccurred(fleet: CampaignFleetAPI?,
                                      primaryWinner: CampaignFleetAPI?, battle: BattleAPI?) {
    }

    fun applyControllerHullmod() {
        if (fleet.fleetData?.membersListCopy == null) return
        for (member in fleet.fleetData.membersListCopy) {
            if (!member.variant.hasHullMod("sc_skill_controller")) {
                if (member.variant.source != VariantSource.REFIT) {
                    var variant = member.variant.clone()
                    variant.originalVariant = null
                    variant.hullVariantId = Misc.genUID()
                    variant.source = VariantSource.REFIT
                    member.setVariant(variant, false, true)
                }
                member.variant.addMod("sc_skill_controller")
            }
        }
    }

    var blacklist = listOf(
        "tactical_drills",
        "coordinated_maneuvers",
        "wolfpack_tactics",
        "crew_training",
        "fighter_uplink",
        "carrier_group",
        "officer_training",
        "officer_management",
        "best_of_the_best",
        "support_doctrine",
        "electronic_warfare",
        "flux_regulation",
        "cybernetic_augmentation",
        "phase_corps",
        "derelict_contingent",
    )

    fun clearCommanderSkills() {
        var skills = commander.stats.skillsCopy.filter { it.level >= 0.1f }.filterNotNull()
        for (skill in ArrayList(skills)) {
            var id = skill.skill?.id ?: "skill_not_found"
            if (blacklist.contains(id)) {
                skill.level = 0f
                commander.stats.decreaseSkill(skill.skill.id)
            }
        }
    }
}