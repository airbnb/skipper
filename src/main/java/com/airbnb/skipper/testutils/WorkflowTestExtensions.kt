package com.airbnb.skipper.testutils

import com.airbnb.skipper.InvocationBuilder
import com.airbnb.skipper.Workflow

/** `workflowBuilder<MyWorkflow>()`: reified form of [WorkflowTest.workflowBuilder]. */
inline fun <reified T : Workflow> WorkflowTest.workflowBuilder(): InvocationBuilder<T> = workflowBuilder(T::class.java)

/** `workflow<MyWorkflow>()`: reified form of [WorkflowTest.workflow]. */
inline fun <reified T : Workflow> WorkflowTest.workflow(): T = workflow(T::class.java)
