package com.airbnb.skipper.internal

import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.internal.reflection.ReflectionUtils

/**
 * Whether this instance's workflow method declares `@WorkflowMethod(compensateOnCancel = true)`.
 * Resolved from the persisted class and method name, so it can be read long after the run started.
 */
internal fun WorkflowInstance.compensatesOnCancel(): Boolean =
    ReflectionUtils.getAllDeclaredMethods(workflowClass, Workflow::class.java).any { method ->
        method.name == workflowMethod &&
            method.getAnnotation(WorkflowMethod::class.java)?.compensateOnCancel == true
    }
