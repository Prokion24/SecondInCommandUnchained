package second_in_command.misc.backgrounds

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.FactionSpecAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import exerelin.campaign.backgrounds.BaseCharacterBackground
import exerelin.utilities.NexFactionConfig
import second_in_command.SCUtils
import second_in_command.misc.SCSettings
import second_in_command.misc.randomAndRemove
import second_in_command.specs.SCAptitudeSpec
import second_in_command.specs.SCBaseAptitudePlugin
import second_in_command.specs.SCSpecStore

class AssociatesBackground : BaseCharacterBackground() {

    override fun getShortDescription(factionSpec: FactionSpecAPI?, factionConfig: NexFactionConfig?): String {
        return "You are bound to experience the sector with a particular kind of deck crew, each with their own spin on fleet procedures."
    }

    override fun getLongDescription(factionSpec: FactionSpecAPI?, factionConfig: NexFactionConfig?): String {
        return "The associates you have made influence your fleet, in one way or another. Without them you would be nothing, " +
                "and you don't even consider to ever cut ties with any of them, even when accommodating for their strengths turns out to be difficult."
    }

    fun getTooltip(tooltip: TooltipMakerAPI) {
        tooltip.addSpacer(10f)

        val hc = Misc.getHighlightColor()
        val nc = Misc.getNegativeHighlightColor()

        val text = if (SCSettings.unrestrictedAssociates!!) ""
        else "Only aptitudes that are available as a starting option can be randomly selected for this."

        val label = tooltip.addPara(
            "You start the game with random executive officers of different aptitudes (up to ${SCSettings.associatesSlots}). Those officers can never be replaced or removed from your fleet. $text\n\n" +
                    "The players previous experience provides them with an additional skill point for their \"Combat\" aptitude. Due to the executive officers particular nature, their experience gain is reduced by 30%.\n\n" +
                    "This background is not recommended if this is your first time using the \"Second-in-Command\" mod. This start ignores the \"Progression Mode\" that can be enabled in the configs.", 0f)

        label.setHighlight("random executive officers", "up to ${SCSettings.associatesSlots}", "can never be replaced or removed", "additional skill point", "Combat", "30%", "This background is not recommended if this is your first time using the \"Second-in-Command\" mod." )
        label.setHighlightColors(hc, hc, nc, hc, hc, nc, nc)
    }

    override fun addTooltipForSelection(tooltip: TooltipMakerAPI?, factionSpec: FactionSpecAPI?, factionConfig: NexFactionConfig?, expanded: Boolean) {
        super.addTooltipForSelection(tooltip, factionSpec, factionConfig, expanded)
        getTooltip(tooltip!!)
    }

    override fun addTooltipForIntel(tooltip: TooltipMakerAPI?, factionSpec: FactionSpecAPI?, factionConfig: NexFactionConfig?) {
        super.addTooltipForIntel(tooltip, factionSpec, factionConfig)
        getTooltip(tooltip!!)
    }

    override fun onNewGameAfterTimePass(factionSpec: FactionSpecAPI?, factionConfig: NexFactionConfig?) {
        val data = SCUtils.getPlayerData()

        // Получаем все доступные аптитуды
        var availableAptitudes = SCSpecStore.getAptitudeSpecs()
            .map { it.getPlugin() }
            .filter { !it.tags.contains("restricted") }
            .toMutableList()

        if (!SCSettings.unrestrictedAssociates!!) {
            availableAptitudes = availableAptitudes.filter { it.tags.contains("startingOption") }.toMutableList()
        }

        val maxOfficers = minOf(
            SCSettings.associatesSlots,
            availableAptitudes.size
        )

        val selectedOfficers = mutableListOf<SCBaseAptitudePlugin>()

        // Режим без ограничений по категориям
        if (true) {
            // Просто выбираем случайные аптитуды без учёта категорий
            repeat(maxOfficers) {
                val pick = availableAptitudes.randomAndRemove() ?: return@repeat
                selectedOfficers.add(pick)
            }
        }
        // Режим с ограничениями по категориям
        else {
            val categorizedAptitudes = availableAptitudes.filter { it.categories.isNotEmpty() }
            val universalAptitudes = availableAptitudes.filter { it.categories.isEmpty() }.toMutableList()

            // 1. Сначала выбираем по одному представителю от каждой категории
            val uniqueCategories = categorizedAptitudes
                .flatMap { it.categories }
                .distinct()
                .shuffled()

            for (category in uniqueCategories) {
                if (selectedOfficers.size >= maxOfficers) break

                val aptitudesInCategory = categorizedAptitudes.filter { it.categories.contains(category) }
                val pick = aptitudesInCategory.randomOrNull() ?: continue

                selectedOfficers.add(pick)
                availableAptitudes.remove(pick)
            }

            // 2. Затем добавляем универсальные аптитуды (без категорий)
            val remainingUniversalSlots = maxOfficers - selectedOfficers.size
            repeat(remainingUniversalSlots) {
                val pick = universalAptitudes.randomAndRemove() ?: return@repeat
                selectedOfficers.add(pick)
            }
        }

        for (pick in selectedOfficers) {
            var officer = SCUtils.createRandomSCOfficer(pick.id)

            officer.person.memoryWithoutUpdate.set("\$sc_associatesOfficer", true)

            data.addOfficerToFleet(officer);
            data.setOfficerInEmptySlotIfAvailable(officer, true)
        }

        // Даём бонусный скилл-поинт
        Global.getSector().characterData.person.stats.points += 1
        Global.getSector().memoryWithoutUpdate.set("\$sc_selectedStart", true)
    }


    companion object {
        //Called if you have an associates run with 4XOs active
        fun fillMissingSlot() {
            var data = SCUtils.getPlayerData()

            if (!SCUtils.isAssociatesBackgroundActive()) return
            //if (!SCSettings.enable4thSlot) return
            var max = SCSettings.playerOfficerSlots
            while (data.getAssignedOfficers().filterNotNull().size < max) {
                var aptitudes = SCSpecStore.getAptitudeSpecs().map { it.getPlugin() }.filter { !it.tags.contains("restricted") }.toMutableList()
                if (!SCSettings.unrestrictedAssociates!!) {
                    aptitudes = aptitudes.filter { it.tags.contains("startingOption") }.toMutableList() //Only pick aptitudes available from the starting interaction
                }

                aptitudes = aptitudes.filter { !data.hasAptitudeInFleet(it.id) }.toMutableList()

                if (aptitudes.isEmpty()) {
                    break //Break out of infinite loop if not enough aptitudes are found
                }

                var picks = ArrayList<SCBaseAptitudePlugin>()

                for (aptitude in aptitudes) {

                    var valid = true

                    for (category in aptitude.categories) {
                        for (active in data.getActiveOfficers()) {
                            if (active.getAptitudePlugin().categories.contains(category)) {
                                valid = false
                            }
                        }
                    }
                    if (valid) picks.add(aptitude)
                }

                var pick = picks.randomOrNull() ?: return

                var officer = SCUtils.createRandomSCOfficer(pick.id)
                officer.person.memoryWithoutUpdate.set("\$sc_associatesOfficer", true)

                data.addOfficerToFleet(officer);
                data.setOfficerInEmptySlotIfAvailable(officer, true)
            }



        }
    }






}