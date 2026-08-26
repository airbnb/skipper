package com.airbnb.skipper.statemachine.admin

import io.dropwizard.views.View
import java.nio.charset.StandardCharsets

class StateMachineAdminView : View("statemachine_admin.ftl", StandardCharsets.UTF_8)
