package com.ing.baker.java_api;

import javax.inject.Qualifier;
import java.lang.annotation.*;

@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
/**
 * An annotation to be added to an argument of an action indicating that the process identifier should be injected
 * there.
 */
public @interface ProcessId {
}
