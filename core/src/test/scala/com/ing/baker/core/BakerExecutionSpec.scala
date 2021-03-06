package com.ing.baker.core

import java.util.UUID

import akka.actor.ActorSystem
import akka.persistence.inmemory.extension.{InMemoryJournalStorage, StorageExtension}
import akka.testkit.{TestKit, TestProbe}
import com.ing.baker._
import com.ing.baker.compiler.ValidationSettings
import com.ing.baker.scala_api._
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.time.{Milliseconds, Span}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success

class BakerExecutionSpec extends TestRecipeHelper {

  implicit val timeout: FiniteDuration = 10 seconds

  before {
    resetMocks

    // Clean inmemory-journal before each test
    val tp = TestProbe()(defaultActorSystem)
    tp.send(StorageExtension(defaultActorSystem).journalStorage, InMemoryJournalStorage.ClearJournal)
    tp.expectMsg(akka.actor.Status.Success(""))
  }

  "The Baker execution engine" should {

    "throw an IllegalArgumentException if a baking a process with the same identifier twice" in {
      val baker = setupBakerWithRecipe("DuplicateIdentifierRecipe")

      val id = UUID.randomUUID()
      baker.bake(id)
      a[IllegalArgumentException] should be thrownBy {
        baker.bake(id)
      }
    }

    //TODO include support for an implicit start event
    "execute an interaction that has no requirements directly when baking starts" ignore {
      when(testInteractionOneMock.apply(anyString(), anyString()))
        .thenReturn(interactionOneIngredient)
      when(testInteractionTwoMock.apply(anyString()))
        .thenReturn(EventFromInteractionTwo(interactionTwoIngredient))

      val recipe = SRecipe(
        name = "directStartRecipe",
        interactions = Seq(
          InteractionDescriptorFactory[InteractionOne]()
            .withOverriddenOutputIngredientName("interactionOneIngredient")
            .withPredefinedIngredients("initialIngredient" -> initialIngredient),
          InteractionDescriptorFactory[InteractionTwo]
            .withPredefinedIngredients("initialIngredient" -> initialIngredient)
        ),
        events = Set(classOf[InitialEvent])
      )

      val baker = new Baker(recipe,
                            mockImplementations,
                            ValidationSettings.defaultValidationSettings,
                            defaultActorSystem)

      val processId = UUID.randomUUID()
      baker.bake(processId)

      verify(testInteractionOneMock).apply(processId.toString, initialIngredient)
      verify(testInteractionTwoMock).apply(initialIngredient)

      val result = baker.getProcessState(processId).ingredients
      result shouldBe Map(
        "initialIngredient"        -> initialIngredient,
        "interactionOneIngredient" -> interactionOneIngredient,
        "interactionTwoIngredient" -> interactionTwoIngredient
      )
    }

    "throw a NoSuchProcessException when requesting the state of a non existing process" in {

      val baker = setupBakerWithRecipe("NonExistingProcessTest")

      intercept[NoSuchProcessException] {
        baker.getProcessState(UUID.randomUUID())
      }
    }

    "throw a NoSuchProcessException when attempting to fire an event for a non existing process" in {
      val baker = setupBakerWithRecipe("NonExistingProcessEventTest")

      val event = InitialEvent("initialIngredient")

      intercept[NoSuchProcessException] {
        baker.handleEvent(UUID.randomUUID(), event)
      }

      val response = baker.handleEventAsync(UUID.randomUUID(), event)

      intercept[NoSuchProcessException] {
        Await.result(response.receivedFuture, timeout)
      }

      intercept[NoSuchProcessException] {
        Await.result(response.completedFuture, timeout)
      }
    }

    "execute an interaction when both ingredients are provided (join situation)" in {
      val baker = setupBakerWithRecipe("JoinRecipeForIngredients")

      val processId = UUID.randomUUID()
      baker.bake(processId)

      baker.handleEvent(processId, InitialEvent("initialIngredient"))

      verify(testInteractionOneMock).apply(processId.toString, "initialIngredient")
      verify(testInteractionTwoMock).apply("initialIngredient")
      verify(testInteractionThreeMock).apply("interactionOneIngredient",
                                             "interactionTwoIngredient")
      baker.getIngredients(processId) shouldBe afterInitialState
    }

    "execute an interaction when two events occur (join situation)" in {
      val baker = setupBakerWithRecipe("JoinRecipeForEvents")

      val processId = UUID.randomUUID()
      baker.bake(processId)

      baker.handleEvent(processId, InitialEvent("initialIngredient"))
      baker.handleEvent(processId, SecondEvent())

      verify(testInteractionOneMock).apply(processId.toString, "initialIngredient")
      verify(testInteractionTwoMock).apply("initialIngredient")
      verify(testInteractionThreeMock).apply("interactionOneIngredient",
                                             "interactionTwoIngredient")
      verify(testInteractionFourMock).apply()
      baker.getIngredients(processId) shouldBe finalState
    }

    "execute an interaction when one of the two events occur (OR situation)" in {
      val baker = {
        val recipe = SRecipe(
          name = "ORPreconditionedRecipeForEvents",
          interactions = Seq(
            InteractionDescriptorFactory[InteractionFour]
              .withRequiredOneOfEvents(classOf[InitialEvent], classOf[SecondEvent])
          ),
          events = Set(classOf[InitialEvent], classOf[SecondEvent])
        )

        new Baker(
          recipe = recipe,
          implementations = mockImplementations,
          validationSettings = ValidationSettings.defaultValidationSettings,
          actorSystem = defaultActorSystem
        )
      }

      val firstProcessId = UUID.randomUUID()
      baker.bake(firstProcessId)

      // Fire one of the events for the first process
      baker.handleEvent(firstProcessId, InitialEvent("initialIngredient"))
      verify(testInteractionFourMock).apply()

      // reset interaction mocks and fire the other event for the second process
      resetMocks

      val secondProcessId = UUID.randomUUID()
      baker.bake(secondProcessId)

      baker.handleEvent(secondProcessId, SecondEvent())
      verify(testInteractionFourMock).apply()
    }

    "execute two interactions which depend on same ingredient (fork situation)" in {

      val baker = setupBakerWithRecipe("MultipleInteractionsFromOneIngredient")

      val processId = UUID.randomUUID()
      baker.bake(processId)

      baker.handleEvent(processId, InitialEvent("initialIngredient"))

      verify(testInteractionOneMock).apply(processId.toString, "initialIngredient")
      verify(testInteractionTwoMock).apply("initialIngredient")
    }

    "execute again after first execution completes and ingredient is produced again" in {

      val baker = setupBakerWithRecipe("MultipleInteractionsFromOneIngredient")

      val processId = UUID.randomUUID()
      baker.bake(processId)

      baker.handleEvent(processId, InitialEvent("initialIngredient"))

      verify(testInteractionOneMock, times(1)).apply(processId.toString, "initialIngredient")
      verify(testInteractionTwoMock, times(1)).apply("initialIngredient")

      baker.handleEvent(processId, InitialEvent("initialIngredient"))

      verify(testInteractionOneMock, times(2)).apply(processId.toString, "initialIngredient")
      verify(testInteractionTwoMock, times(2)).apply("initialIngredient")
    }

    "fire parallel transitions simultaneously" in {

      val baker = setupBakerWithRecipe("ParallelExecutionRecipe")

      // Two answers that take 0.5 seconds each!
      when(testInteractionOneMock.apply(anyString(), anyString())).thenAnswer(new Answer[String] {
        override def answer(invocationOnMock: InvocationOnMock): String = {
          Thread.sleep(500)
          interactionOneIngredient
        }
      })

      when(testInteractionTwoMock.apply(anyString()))
        .thenAnswer(new Answer[EventFromInteractionTwo] {
          override def answer(invocationOnMock: InvocationOnMock): EventFromInteractionTwo = {
            Thread.sleep(500)
            EventFromInteractionTwo(interactionTwoIngredient)
          }
        })

      val processId = UUID.randomUUID()

      baker.bake(processId)

      Thread.sleep(2000)

      val executingTimeInMilliseconds = timeBlockInMilliseconds {
        baker.handleEvent(processId, InitialEvent(initialIngredient))
      }

      val tookLessThanASecond = executingTimeInMilliseconds < 1000
      assert(
        tookLessThanASecond,
        s"If it takes less than one second to execute we can be sure the two actions have executed in parallel. " +
          s"The execution took: $executingTimeInMilliseconds milliseconds and have executed sequentially...")
      // Note: this is not related to startup time.
      // Same behaviour occurs if we have actions that take 10 seconds and test if it is less than 20 seconds.
    }

    "update the state with new data if an event occurs twice" in {

      val firstData: String  = "firstData"
      val secondData: String = "secondData"
      val firstResponse      = "firstResponse"
      val secondResponse     = "secondResponse"

      val baker = setupBakerWithRecipe("UpdateTestRecipe")

      val processId = UUID.randomUUID()

      when(testInteractionOneMock.apply(processId.toString, firstData)).thenReturn(firstResponse)
      when(testInteractionOneMock.apply(processId.toString, secondData)).thenReturn(secondResponse)

      baker.bake(processId)

      //Fire the first event
      baker.handleEvent(processId, InitialEvent(firstData))

      //Check that the first response returned
      baker.getProcessState(processId).ingredients shouldBe Map(
        "initialIngredient"          -> firstData,
        "interactionOneIngredient"   -> firstResponse,
        "interactionTwoIngredient"   -> interactionTwoIngredient,
        "interactionThreeIngredient" -> interactionThreeIngredient
      )

      //Fire the second event
      baker.handleEvent(processId, InitialEvent(secondData))

      //Check that the second response is given
      baker.getProcessState(processId).ingredients shouldBe Map(
        "initialIngredient"          -> secondData,
        "interactionOneIngredient"   -> secondResponse,
        "interactionTwoIngredient"   -> interactionTwoIngredient,
        "interactionThreeIngredient" -> interactionThreeIngredient
      )
    }

    "only fire an interaction once if it has an maximum interaction count of 1" in {

      val recipe = SRecipe(
        name = "FiringLimitTestRecipe",
        interactions = Seq(
          InteractionDescriptorFactory[InteractionOne]
            .withOverriddenOutputIngredientName("interactionOneIngredient")
            .withMaximumInteractionCount(1)),
        events = Set(classOf[InitialEvent])
      )

      when(testInteractionOneMock.apply(anyString(), anyString()))
        .thenReturn(interactionOneIngredient)

      val baker = new Baker(recipe,
                            mockImplementations,
                            ValidationSettings.defaultValidationSettings,
                            defaultActorSystem)
      val processId = UUID.randomUUID()
      baker.bake(processId)

      baker.handleEvent(processId, InitialEvent(initialIngredient))

      verify(testInteractionOneMock).apply(processId.toString, initialIngredient)

      val result = baker.getProcessState(processId).ingredients
      result shouldBe Map("initialIngredient"        -> initialIngredient,
                          "interactionOneIngredient" -> interactionOneIngredient)

      baker.handleEvent(processId, InitialEvent(initialIngredient))

      verifyZeroInteractions(testInteractionOneMock)
    }

    "not throw an exception when an event is fired and a resulting interactions fails" in {
      val baker = setupBakerWithRecipe("FailingInteraction")
      when(testInteractionOneMock.apply(anyString, anyString()))
        .thenThrow(new RuntimeException(errorMessage))

      val processId = UUID.randomUUID()
      baker.bake(processId)
      baker.handleEvent(processId, InitialEvent(initialIngredient))
    }

    "not crash when one process crashes but the other does not" in {

      val baker = setupBakerWithRecipe("CrashTestRecipe")

      val firstProcessId  = UUID.randomUUID()
      val secondProcessId = UUID.randomUUID()
      when(testInteractionOneMock.apply(firstProcessId.toString, initialIngredient))
        .thenReturn(interactionOneIngredient)
      when(testInteractionOneMock.apply(secondProcessId.toString, initialIngredient))
        .thenThrow(new RuntimeException(errorMessage))
      baker.bake(firstProcessId)
      baker.bake(secondProcessId)

      // start the first process with firing an event
      baker.handleEvent(firstProcessId, InitialEvent(initialIngredient))

      // start the second process and expect a failure
      baker.handleEvent(secondProcessId, InitialEvent(initialIngredient))

      // fire another event for the first process
      baker.handleEvent(firstProcessId, SecondEvent())

      // expect first process state is correct
      baker.getProcessState(firstProcessId).ingredients shouldBe finalState
    }

    "keep the input data in accumulated state even if the interactions dependent on this event fail to execute" in {

      val baker     = setupBakerWithRecipe("StatePersistentTestRecipe")
      val processId = UUID.randomUUID()
      when(testInteractionOneMock.apply(processId.toString, initialIngredient))
        .thenThrow(new RuntimeException(errorMessage))
      baker.bake(processId)

      // Send failing event and after that send succeeding event
      baker.handleEvent(processId, InitialEvent(initialIngredient))

      val result = baker.getProcessState(processId).ingredients
      result shouldBe Map("initialIngredient"        -> initialIngredient,
                          "interactionTwoIngredient" -> interactionTwoIngredient)
    }

    "retry an interaction with incremental backoff if configured to do so" in {

      val baker = setupBakerWithRecipe("FailingInteractionWithBackof")
      when(testInteractionOneMock.apply(anyString(), anyString()))
        .thenThrow(new RuntimeException(errorMessage))

      val processId = UUID.randomUUID()
      baker.bake(processId)

      baker.handleEvent(processId, InitialEvent(initialIngredient))

      //Thread.sleep is needed since we need to wait for the expionental back of
      //100ms should be enough since it waits 20ms and then 40 ms
      Thread.sleep(200)
      //Since it can be called up to 3 times it should have been called 3 times in the 100ms
      verify(testInteractionOneMock, times(3)).apply(processId.toString, initialIngredient)
    }

    "not execute the failing interaction again each time after some other unrelated event is fired" in {

      /* This test FAILS because passportData FAIL_DATA is included in the marking while it should not! (?)
       * The fact that it is in the marking forces failingUploadPassport to fire again when second event fires!
       */
      val baker     = setupBakerWithRecipe("ShouldNotReExecute")
      val processId = UUID.randomUUID()

      when(testInteractionTwoMock.apply(anyString())).thenThrow(new RuntimeException(errorMessage))
      baker.bake(processId)

      // first fired event causes a failure in the action
      baker.handleEvent(processId, InitialEvent(initialIngredient))
      verify(testInteractionTwoMock, times(1)).apply(anyString())
      resetMocks

      // second fired, this should not re-execute InteractionOne and therefor not start InteractionThree
      baker.handleEvent(processId, SecondEvent())

      verify(testInteractionTwoMock, never()).apply(anyString())

      val result = baker.getProcessState(processId).ingredients
      result shouldBe Map("initialIngredient"        -> initialIngredient,
                          "interactionOneIngredient" -> interactionOneIngredient)
    }

    //Only works if persistence actors are used (think cassandra)
    "recover the state of a process from a persistence store" in {
      val system1 = ActorSystem("persistenceTest1", levelDbConfig)

      val recoveryRecipeName = "RecoveryRecipe"
      val baker1 = setupBakerWithRecipe(recoveryRecipeName, system1, appendUUIDToTheRecipeName = false)

      val processId = UUID.randomUUID()
      baker1.bake(processId)
      baker1.handleEvent(processId, InitialEvent(initialIngredient))
      baker1.handleEvent(processId, SecondEvent())

      baker1.getProcessState(processId).ingredients shouldBe finalState

      TestKit.shutdownActorSystem(system1)

      val system2 = ActorSystem("persistenceTest2", levelDbConfig)
      val baker2 = new Baker(getComplexRecipe(recoveryRecipeName),
                             mockImplementations,
                             ValidationSettings.defaultValidationSettings,
                             system2)
      baker2.getProcessState(processId).ingredients shouldBe finalState

      TestKit.shutdownActorSystem(system2)
    }

    "when acknowledging the first event, not wait on the rest" in {
      val baker = setupBakerWithRecipe("NotWaitForTheRest", defaultActorSystem)

      val interaction2Delay = 2000

      when(testInteractionTwoMock.apply(anyString())).thenAnswer {
        new Answer[EventFromInteractionTwo] {
          override def answer(invocation: InvocationOnMock): EventFromInteractionTwo = {
            Thread.sleep(interaction2Delay)
            interactionTwoEvent
          }
        }
      }

      val processId = UUID.randomUUID()
      baker.bake(processId)
      val response = baker.handleEventAsync(processId, InitialEvent(initialIngredient))

      import org.scalatest.concurrent.Timeouts._

      failAfter(Span(500, Milliseconds)) {
        Await.result(response.receivedFuture, 500 millis)
        response.completedFuture.isCompleted shouldEqual false
      }

      Await.result(response.completedFuture, 3000 millis)

      response.completedFuture.value should matchPattern { case Some(Success(_)) => }
    }

    "acknowledge the first and final event while rest processing failed" in {
      val baker = setupBakerWithRecipe("AcknowledgeThefirst", defaultActorSystem)

      when(testInteractionTwoMock.apply(anyString()))
        .thenThrow(new RuntimeException("Unknown Exception."))

      val processId = UUID.randomUUID()
      baker.bake(processId)
      val response = baker.handleEventAsync(processId, InitialEvent(initialIngredient))
      Await.result(response.completedFuture, 3 seconds)
      response.receivedFuture.value should matchPattern { case Some(Success(())) => }
      response.completedFuture.value should matchPattern { case Some(Success(())) => }
    }

//    "keep a set of all process ids for efficient querying" in {
//
//      val baker = setupBakerWithRecipe("EfficientQueryingOfProcessIds")
//
//      val processIds = Stream.fill(10)(UUID.randomUUID()).toSet
//      processIds foreach baker.bake
//
//      println(s"Count ${baker.allProcessIds.size}")
//      baker.allProcessIds shouldBe processIds.map(_.toString)
//
//      val baker2 = setupBakerWithRecipe("EfficientQueryingOfProcessIds")
//      // Here we expect all the previous process ids are already loaded during baker construction
//      println(s"Count2 ${baker2.allProcessIds.size}")
//      baker2.allProcessIds shouldBe processIds.map(_.toString)
//    }
//
  }
}
