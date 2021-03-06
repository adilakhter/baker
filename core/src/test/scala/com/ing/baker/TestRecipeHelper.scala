package com.ing.baker

import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.ing.baker.api.Event
import com.ing.baker.compiler.ValidationSettings
import com.ing.baker.core.{Baker, Interaction}
import com.ing.baker.java_api.{FiresEvent, ProcessId, ProvidesIngredient, RequiresIngredient}
import com.ing.baker.scala_api._
import com.typesafe.config.{Config, ConfigFactory}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mock.MockitoSugar

import scala.concurrent.duration._
import scala.language.postfixOps

//All events that are used in the recipes for the test
case class InitialEvent(initialIngredient: String) extends Event
case class SecondEvent() extends Event
case class NotUsedSensoryEvent() extends Event
case class EventFromInteractionTwo(interactionTwoIngredient: String) extends Event
case class EventWithANonSerializableIngredient(nonSerializableObject: NonSerializableObject) extends Event
class NonSerializableObject()

//All the interaction operations that are used in the recipes for the tests
trait InteractionOne extends Interaction {
  @ProvidesIngredient("interactionOneOriginalIngredient")
  def apply(@ProcessId id: String,
            @RequiresIngredient("initialIngredient") message: String): String
}

trait InteractionTwo extends Interaction {
  @FiresEvent(oneOf = Array(classOf[EventFromInteractionTwo]))
  def apply(@RequiresIngredient("initialIngredientOld") message: String): EventFromInteractionTwo
}

trait InteractionThree extends Interaction {
  @ProvidesIngredient("interactionThreeIngredient")
  def apply(@RequiresIngredient("interactionOneIngredient") actionOneIngredient: String,
            @RequiresIngredient("interactionTwoIngredient") actionTwoIngredient: String): String
}
trait InteractionFour extends Interaction {
  @ProvidesIngredient("interactionFourIngredient")
  def apply(): String
}
trait NonSerializableEventInteraction extends Interaction {
  @FiresEvent(oneOf = Array(classOf[NonSerializableObject]))
  def apply(@RequiresIngredient("initialIngredient") message: String): NonSerializableObject
}
trait NonSerializableIngredientInteraction extends Interaction {
  @ProvidesIngredient("nonSerializableIngredient")
  def apply(@RequiresIngredient("initialIngredient") message: String): NonSerializableObject
}

trait NonMatchingReturnTypeInteraction extends Interaction {
  @FiresEvent(oneOf = Array(classOf[EventFromInteractionTwo]))
  def apply(@RequiresIngredient("initialIngredient") message: String): String
}

trait TestRecipeHelper
    extends WordSpecLike
    with Matchers
    with MockitoSugar
    with BeforeAndAfter
    with BeforeAndAfterAll {
  //Values to use for setting and checking the ingredients

  //Default values to be used for the ingredients in the tests
  protected val initialIngredient                = "initialIngredient"
  protected val interactionOneOriginalIngredient = "interactionOneOriginalIngredient"
  protected val interactionOneIngredient         = "interactionOneIngredient"
  protected val interactionTwoIngredient         = "interactionTwoIngredient"
  protected val interactionTwoEvent              = EventFromInteractionTwo(interactionTwoIngredient)
  protected val interactionThreeIngredient       = "interactionThreeIngredient"
  protected val interactionFourIngredient        = "interactionFourIngredient"
  protected val errorMessage                     = "This is the error message"

  //Can be used to check the state after firing the initialEvent
  protected val afterInitialState = Map(
    "initialIngredient"          -> initialIngredient,
    "interactionOneIngredient"   -> interactionOneIngredient,
    "interactionTwoIngredient"   -> interactionTwoIngredient,
    "interactionThreeIngredient" -> interactionThreeIngredient
  )

  //Can be used to check the state after firing the initialEvent and SecondEvent
  protected val finalState = Map(
    "initialIngredient"          -> initialIngredient,
    "interactionOneIngredient"   -> interactionOneIngredient,
    "interactionTwoIngredient"   -> interactionTwoIngredient,
    "interactionThreeIngredient" -> interactionThreeIngredient,
    "interactionFourIngredient"  -> interactionFourIngredient
  )

  protected val testInteractionOneMock: InteractionOne     = mock[InteractionOne]
  protected val testInteractionTwoMock: InteractionTwo     = mock[InteractionTwo]
  protected val testInteractionThreeMock: InteractionThree = mock[InteractionThree]
  protected val testInteractionFourMock: InteractionFour   = mock[InteractionFour]
  protected val testNonSerializableEventInteractionMock: NonSerializableEventInteraction =
    mock[NonSerializableEventInteraction]
  protected val testNonSerializableIngredientInteractionMock: NonSerializableIngredientInteraction =
    mock[NonSerializableIngredientInteraction]
  protected val testNonMatchingReturnTypeInteractionMock: NonMatchingReturnTypeInteraction =
    mock[NonMatchingReturnTypeInteraction]

  protected val mockImplementations: Map[Class[_], () => AnyRef] =
    Map(
      classOf[InteractionOne]   -> (() => testInteractionOneMock),
      classOf[InteractionTwo]   -> (() => testInteractionTwoMock),
      classOf[InteractionThree] -> (() => testInteractionThreeMock),
      classOf[InteractionFour]  -> (() => testInteractionFourMock),
      classOf[NonSerializableIngredientInteraction] -> (() =>
                                                          testNonSerializableIngredientInteractionMock),
      classOf[NonSerializableEventInteraction] -> (() => testNonSerializableEventInteractionMock),
      classOf[NonMatchingReturnTypeInteraction] -> (() =>
                                                      testNonMatchingReturnTypeInteractionMock))

  protected val localConfig: Config =
    ConfigFactory.parseString("""
      |akka {
      |   persistence {
      |    journal.plugin = "inmemory-journal"
      |    snapshot-store.plugin = "inmemory-snapshot-store"
      |
      |    auto-start-snapshot-stores = [ "inmemory-snapshot-store"]
      |    auto-start-journals = [ "inmemory-journal" ]
      |  }
      |  log-config-on-start = off
      |  serialize-messages = on
      |}
      |
      |baker {
      |  actor.provider = "local"
      |}
      |
      |logging.root.level = DEBUG
    """.stripMargin)

  protected val levelDbConfig: Config = ConfigFactory.parseString(
    """
      |akka {
      |   persistence {
      |     journal.plugin = "akka.persistence.journal.leveldb"
      |     journal.leveldb.dir = "target/journal"
      |
      |     snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      |     snapshot-store.local.dir = "target/snapshots"
      |
      |     auto-start-snapshot-stores = [ "akka.persistence.snapshot-store.local"]
      |     auto-start-journals = [ "akka.persistence.journal.leveldb" ]
      |
      |     journal.leveldb.native = off
      |  }
      |  log-config-on-start = off
      |  serialize-messages = on
      |}
      |
      |baker {
      |  actor.provider = "local"
      |  actor.read-journal-plugin = "akka.persistence.query.journal.leveldb"
      |}
      |
      |logging.root.level = DEBUG
    """.stripMargin)

  protected val defaultActorSystem = ActorSystem("BakerSpec", localConfig)

  override def afterAll(): Unit = { TestKit.shutdownActorSystem(defaultActorSystem) }

  protected def getComplexRecipe(recipeName: String): SRecipe = {
    SRecipe(
      name = recipeName,
      interactions = Seq(
        InteractionDescriptorFactory[InteractionOne]()
          .withOverriddenOutputIngredientName("interactionOneIngredient")
          .withIncrementalBackoffOnFailure(initialDelay = 10 millisecond, maximumRetries = 3),
        InteractionDescriptorFactory[InteractionTwo]
          .withOverriddenIngredientName("initialIngredientOld", "initialIngredient"),
        InteractionDescriptorFactory[InteractionThree]
          .withMaximumInteractionCount(1),
        InteractionDescriptorFactory[InteractionFour]
          .withRequiredEvent[SecondEvent].withRequiredEvent[EventFromInteractionTwo]
      ),
      events = Set(classOf[InitialEvent], classOf[SecondEvent], classOf[NotUsedSensoryEvent])
    )
  }

  /**
    * Returns a Baker instance that contains a simple recipe that can be used in tests
    * It als sets mocks that return happy flow responses for the interactions
    *
    * This recipe contains:
    * A split from 1 ingredient to two interactions
    * A join after the two interactions that give out ingredients into a third interaction
    To See a visualisation of the recipe go to http://www.webgraphviz.com/ and use the following:

    digraph {
      node [fontname = "ING Me", fontsize = 22, fontcolor = white]
      pad = 0.2
      interactionTwo -> EventFromInteractionTwo
      interactionOneIngredient [shape = circle, color = "#FF6200", style = filled]
      EventFromInteractionTwo -> interactionTwoIngredient
      interactionTwoIngredient -> interactionThree
      interactionTwo [shape = rect, margin = 0.5, color = "#525199", style = "rounded, filled", penwidth = 2]
      interactionThree [shape = rect, margin = 0.5, color = "#525199", style = "rounded, filled", penwidth = 2]
      interactionFour [shape = rect, margin = 0.5, color = "#525199", style = "rounded, filled", penwidth = 2]
      interactionFourIngredient [shape = circle, color = "#FF6200", style = filled]
      initialIngredient -> "multi:initialIngredient"
      SecondEvent -> interactionFour
      interactionThreeIngredient [shape = circle, color = "#FF6200", style = filled]
      InitialEvent -> initialIngredient
      interactionThree -> interactionThreeIngredient
      interactionFour -> interactionFourIngredient
      interactionOne [shape = rect, margin = 0.5, color = "#525199", style = "rounded, filled", penwidth = 2]
      interactionOne -> interactionOneIngredient
      InitialEvent [shape = diamond, margin = 0.3, style = "rounded, filled", color = "#767676"]
      "multi:initialIngredient" -> interactionTwo
      SecondEvent [shape = diamond, margin = 0.3, style = "rounded, filled", color = "#767676"]
      interactionTwoIngredient [shape = circle, color = "#FF6200", style = filled]
      "multi:initialIngredient" [shape = point, fillcolor = "#D0D93C", width = 0.3, height = 0.3]
      EventFromInteractionTwo -> interactionFour
      EventFromInteractionTwo [shape = diamond, margin = 0.3, style = "rounded, filled", color = "#767676"]
      interactionOneIngredient -> interactionThree
      initialIngredient [shape = circle, color = "#FF6200", style = filled]
      "multi:initialIngredient" -> interactionOne
    }

    * @param recipeName A unique name that is needed for the recipe to insure that the tests do not interfere with each other
    * @return
    */
  protected def setupBakerWithRecipe(recipeName: String,
                                     actorSystem: ActorSystem = defaultActorSystem,
                                     appendUUIDToTheRecipeName: Boolean = true): Baker = {
    val newRecipeName = if (appendUUIDToTheRecipeName) s"$recipeName-${UUID.randomUUID().toString}" else recipeName
    val recipe = getComplexRecipe(newRecipeName)
    when(testInteractionOneMock.apply(anyString(), anyString()))
      .thenReturn(interactionOneIngredient)
    when(testInteractionTwoMock.apply(anyString())).thenReturn(interactionTwoEvent)
    when(testInteractionThreeMock.apply(anyString(), anyString()))
      .thenReturn(interactionThreeIngredient)
    when(testInteractionFourMock.apply()).thenReturn(interactionFourIngredient)

    new Baker(recipe = recipe,
              validationSettings = ValidationSettings.defaultValidationSettings,
              actorSystem = actorSystem,
              implementations = mockImplementations)
  }

  protected def timeBlockInMilliseconds[T](block: => T): Long = {
    val t0 = System.nanoTime()
    block
    val t1                                  = System.nanoTime()
    val amountOfNanosecondsInOneMillisecond = 1000000
    val milliseconds                        = (t1 - t0) / amountOfNanosecondsInOneMillisecond

    milliseconds
  }

  protected def resetMocks =
    reset(testInteractionOneMock,
          testInteractionTwoMock,
          testInteractionThreeMock,
          testInteractionFourMock,
          testNonMatchingReturnTypeInteractionMock,
          testNonSerializableEventInteractionMock,
          testNonSerializableIngredientInteractionMock)

}
