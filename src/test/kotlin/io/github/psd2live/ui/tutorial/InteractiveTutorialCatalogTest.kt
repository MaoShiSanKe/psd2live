package io.github.psd2live.ui.tutorial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InteractiveTutorialCatalogTest {
	@Test
	fun defaultStateCanInitializeFromTheGuiEntryPath() {
		val state = InteractiveTutorialState()
		assertEquals(TutorialPath.BEGINNER, state.path)
		assertEquals(TutorialId.BASIC, state.tutorialId)
	}

	@Test
	fun progressiveOrderCoversAllIds() {
		assertEquals(TutorialId.entries.toSet(), TutorialId.progressiveOrder.toSet())
		assertEquals(TutorialId.WORKSPACE, TutorialPath.BEGINNER.nextAfter(TutorialId.BASIC))
		assertEquals(TutorialId.WORKSPACE, TutorialPath.EXPERIENCED.nextAfter(TutorialId.LIVE2D_BRIDGE))
		assertEquals(null, TutorialPath.BEGINNER.nextAfter(TutorialId.TEXTURE_UPSCALE))
	}

	@Test
	fun everyTutorialHasStepsAndDone() {
		TutorialId.entries.forEach { id ->
			val def = tutorialDefinition(id)
			assertTrue(def.steps.isNotEmpty(), id.name)
			assertTrue(def.steps.last().isDone, id.name)
			assertTrue(def.actionableSteps.isNotEmpty(), id.name)
			assertFalse(def.actionableSteps.any { it.isDone }, id.name)
		}
	}

	@Test
	fun advanceAndContinueNextTutorial() {
		var state = InteractiveTutorialState().start(TutorialId.BASIC, TutorialPath.BEGINNER)
		assertTrue(state.active)
		assertEquals(TutorialId.BASIC, state.tutorialId)
		while (!state.isDoneStep) {
			state = state.advance()
			assertTrue(state.active)
		}
		state = state.continueNextTutorial()
		assertEquals(TutorialId.WORKSPACE, state.tutorialId)
		assertEquals(0, state.stepIndex)
	}

	@Test
	fun experiencedPathKeepsItsTrackWhenContinuing() {
		var state = InteractiveTutorialState().start(TutorialId.LIVE2D_BRIDGE, TutorialPath.EXPERIENCED)
		while (!state.isDoneStep) state = state.advance()
		state = state.continueNextTutorial()
		assertEquals(TutorialPath.EXPERIENCED, state.path)
		assertEquals(TutorialId.WORKSPACE, state.tutorialId)
	}

	@Test
	fun retreatDoesNotGoBeforeStart() {
		val state = InteractiveTutorialState().start(TutorialId.HIERARCHY).retreat()
		assertEquals(0, state.stepIndex)
		assertNotNull(state.step)
	}

	@Test
	fun hierarchyIncludesImportAndPlacementSteps() {
		val keys = tutorialDefinition(TutorialId.HIERARCHY).steps.map { it.key }
		assertTrue("importLayer" in keys)
		assertTrue("placeSession" in keys)
		assertEquals("done", keys.last())
	}

	@Test
	fun workspaceLessonCoversPresetsDockingSplitsAndFloating() {
		assertEquals(
			listOf("presets", "switch", "dockTabs", "split", "float", "memory", "sidebars", "viewMenu", "camera", "done"),
			tutorialDefinition(TutorialId.WORKSPACE).steps.map { it.key },
		)
		assertEquals(TutorialTargetId.WORKSPACE_STRIP, tutorialDefinition(TutorialId.WORKSPACE).steps.first().targetId)
		assertEquals(TutorialTargetId.DOCK_AREA, tutorialDefinition(TutorialId.WORKSPACE).steps.first { it.key == "dockTabs" }.targetId)
	}

	@Test
	fun variantsCoversToggleAndSwitchFlow() {
		assertEquals(
			listOf("types", "toggleWhy", "toggleSetup", "switchWhy", "switchSetup", "done"),
			tutorialDefinition(TutorialId.VARIANTS).steps.map { it.key },
		)
	}

	@Test
	fun parametersCoversPanelDragKeysAndLink() {
		assertEquals(
			listOf("findTab", "panel", "drag", "keys", "operate", "link", "done"),
			tutorialDefinition(TutorialId.PARAMETERS).steps.map { it.key },
		)
	}

	@Test
	fun createDeformerUsesTreeMenusAndPlacement() {
		val steps = tutorialDefinition(TutorialId.CREATE_DEFORMER).steps
		assertEquals(
			listOf("selectFirst", "contextDeformer", "contextLayer", "placement", "done"),
			steps.map { it.key },
		)
		assertTrue(steps[0].requireSelection)
		assertEquals(TutorialTargetId.PLACEMENT_PANEL, steps[3].targetId)
	}

	@Test
	fun projectHistoryAndUpscaleTargetsAreCorrect() {
		val history = tutorialDefinition(TutorialId.PROJECT_HISTORY).steps.first { it.key == "historyTab" }
		assertEquals(TutorialTargetId.HISTORY_TAB, history.targetId)
		val entry = tutorialDefinition(TutorialId.TEXTURE_UPSCALE).steps.first { it.key == "entry" }
		assertEquals(TutorialTargetId.TOOLS_TEXTURE_UPSCALE, entry.targetId)
		assertEquals("tools", entry.forcesMenu)
	}

	@Test
	fun deformBrushesRequireSelection() {
		val brushes = tutorialDefinition(TutorialId.DEFORM_MODE).steps.first { it.key == "brushes" }
		assertTrue(brushes.requireSelection)
		assertTrue(brushes.showAction)
	}

	@Test
	fun newFeatureLessonsUseCanvasAndTheirRealDocks() {
		assertEquals(TutorialTargetId.SKELETON_DOCK, tutorialDefinition(TutorialId.SKELETON).steps.first().targetId)
		assertTrue(tutorialDefinition(TutorialId.SKELETON).steps.any { it.targetId == TutorialTargetId.CANVAS_VIEWPORT })
		assertTrue(tutorialDefinition(TutorialId.SKELETON).steps.any { it.key == "sampling" })
		assertEquals(TutorialTargetId.ANIMATION_DOCK, tutorialDefinition(TutorialId.ANIMATION).steps.first().targetId)
		assertTrue(tutorialDefinition(TutorialId.ANIMATION).steps.any { it.targetId == TutorialTargetId.ANIMATION_EDITOR_DOCK })
		assertTrue(tutorialDefinition(TutorialId.ANIMATION).steps.any { it.key == "autoKey" })
		assertEquals(TutorialTargetId.PHYSICS_DOCK, tutorialDefinition(TutorialId.PHYSICS).steps.first().targetId)
		assertTrue(tutorialDefinition(TutorialId.PHYSICS).steps.any { it.targetId == TutorialTargetId.CANVAS_VIEWPORT })
	}

	@Test
	fun allTutorialStepsHaveTranslationsAcrossLocales() {
		val bundles = listOf("Messages", "Messages_zh_CN", "Messages_ja").map { name ->
			val props = java.util.Properties()
			javaClass.getResourceAsStream("/i18n/$name.properties")!!.reader(Charsets.UTF_8).use(props::load)
			name to props
		}
		val missing = mutableListOf<String>()
		TutorialId.entries.forEach { id ->
			val def = tutorialDefinition(id)
			bundles.forEach { (bundleName, props) ->
				if (props.getProperty(id.titleKey).orEmpty().isBlank()) missing += "$bundleName missing ${id.titleKey}"
				if (props.getProperty(id.descKey).orEmpty().isBlank()) missing += "$bundleName missing ${id.descKey}"
				def.steps.forEach { step ->
					val titleK = step.titleKey(id)
					val bodyK = step.bodyKey(id)
					if (props.getProperty(titleK).orEmpty().isBlank()) missing += "$bundleName missing $titleK"
					if (props.getProperty(bodyK).orEmpty().isBlank()) missing += "$bundleName missing $bodyK"
					if (step.showAction) {
						val actionK = step.actionKey(id)
						if (props.getProperty(actionK).orEmpty().isBlank()) missing += "$bundleName missing $actionK"
					}
				}
			}
		}
		assertTrue(missing.isEmpty(), "Missing tutorial translations:\n" + missing.joinToString("\n"))
	}

	@Test
	fun startCanvasUpdatesHaveTranslationsAcrossLocales() {
		val bundles = listOf("Messages", "Messages_zh_CN", "Messages_ja").map { name ->
			val props = java.util.Properties()
			javaClass.getResourceAsStream("/i18n/$name.properties")!!.reader(Charsets.UTF_8).use(props::load)
			name to props
		}
		val keys = listOf("animation", "skeleton", "history", "workspace", "physics")
		bundles.forEach { (bundleName, props) ->
			keys.forEach { key ->
				val title = props.getProperty("canvas.start.update.$key.title")
				val desc = props.getProperty("canvas.start.update.$key.desc")
				assertTrue(title.orEmpty().isNotBlank(), "$bundleName missing canvas.start.update.$key.title")
				assertTrue(desc.orEmpty().isNotBlank(), "$bundleName missing canvas.start.update.$key.desc")
			}
		}
	}
}
