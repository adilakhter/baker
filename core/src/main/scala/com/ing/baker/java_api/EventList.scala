package com.ing.baker.java_api

import scala.collection.JavaConverters._

/**
  * Java API class wrapping a sequence of events for a process instance.
  *
  * @param events The event sequence.
  */
class EventList(private val events: Seq[Any]) extends java.util.AbstractList[AnyRef] {

  def this() = this(Seq.empty)

  def this(events: java.util.List[AnyRef]) = this(events.asScala)

  override def get(index: Int): AnyRef = events.apply(index).asInstanceOf[AnyRef]

  override def size(): Int = events.size

  /**
    * Returns whether an event occurred or not.
    *
    * @param clazz The event class.
    *
    * @return
    */
  def eventOccurred(clazz: Class[_]): Boolean = events.exists(e => clazz.isInstance(e))

  /**
    * Returns the number of times an event occurred.
    *
    * @param clazz The event class.
    *
    * @return The number of times an event occurred.
    */
  def eventCount(clazz: Class[_]): Int = events.filter(e => clazz.isInstance(e)).size

  /**
    * Returns a list of the event classes.
    *
    * @return A list of event classes.
    */
  def eventClassList: java.util.List[Class[_]] = events.toList.map(_.getClass).asJava
}
