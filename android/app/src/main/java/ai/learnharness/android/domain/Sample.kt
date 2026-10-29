package ai.learnharness.android.domain

fun sampleSession() = Session(title = "Why does the Moon stay in orbit?", sample = true, messages = listOf(
    Message(role = "user", text = "I want to understand why the Moon doesn't fall to Earth."),
    Message(role = "assistant", text = "An orbit is a continuous fall.", turn = TutorTurn(
        phase = "teach",
        text = "An orbit is a continuous fall.\n\nImagine throwing a ball sideways. Gravity bends its path toward the ground. Throw it faster and it travels farther before landing.\n\nIn an idealized vacuum, at the right sideways speed, Earth's surface curves away as fast as the ball falls toward it. The ball keeps falling, but never reaches the surface.\n\nThe Moon has both sideways motion and an inward gravitational acceleration. Together, they bend its path around Earth.",
        question = "If Earth's gravity suddenly disappeared, what path would the Moon initially follow, and why?",
        nodeId = "orbit",
        plan = listOf(
            PlanNode("inertia", "Motion without a force", "Velocity stays constant when no net force acts."),
            PlanNode("gravity", "Gravity bends motion", "An inward acceleration changes the direction of velocity.", listOf("inertia")),
            PlanNode("orbit", "Falling around Earth", "Sideways motion and gravity together produce an orbit.", listOf("gravity"))
        ),
        concepts = listOf(
            Concept("inertia", "Inertia", "With zero net force, an object's velocity remains constant in an inertial reference frame."),
            Concept("gravity", "Gravity changes velocity", "Earth's gravitational attraction accelerates the Moon toward Earth.", listOf("inertia")),
            Concept("orbit", "An orbit is free fall", "Sideways motion combined with inward gravitational acceleration can produce a bound orbit.", listOf("gravity"))
        )
    ))
))
