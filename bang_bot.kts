name("Bang Bot")
description("Sex Bot")

enum class Phase {
    ACQUIRE,
    WALK_TO_FRONT,
    WALK_TO_TARGET,
    WALK_TO_NEW_FRONT
}

val searchRadius = 128.0
val approachRadius = 2.0
val pointReachDistance = 0.1
val targetBuffer = 0.1
val frontOffset = 0.75
val crouchTogglesPerSecond = 6.0
val cameraSmoothingTicks by slider("Camera Smoothing", 2, 0, 3, 1)
val showPointVisualization by checkBox("Show Point Visualization", false)
val pointVisualizationColor by colorPicker("Point Visualization Color", 0xFFFFFFFFL)
val pointVisualizationRadius = 0.25

var phase = Phase.ACQUIRE
var lockedTargetUuid = ""
var destination: Vec? = null
var crouching = false
var crouchAccumulator = 0.0
var legToggleDone = false
var aimRotation: Rotation? = null

fun wrapDegrees(angle: Float): Float {
    var wrapped = angle % 360.0f
    if (wrapped >= 180.0f) wrapped -= 360.0f
    if (wrapped < -180.0f) wrapped += 360.0f
    return wrapped
}

fun smoothCameraRotation(target: Rotation): Rotation {
    val current = player.rotation()
    if (cameraSmoothingTicks <= 0.0f) return target
    val yawStep = wrapDegrees(target.yawDegrees() - current.yawDegrees()) / cameraSmoothingTicks
    val pitchStep = wrapDegrees(target.pitchDegrees() - current.pitchDegrees()) / cameraSmoothingTicks
    return Rotation(
        current.yawDegrees() + yawStep,
        current.pitchDegrees() + pitchStep
    )
}

on < PrePlayerTickEvent > {
    whenInGame {
        val local = player
        val target = if (lockedTargetUuid.isEmpty()) {
            world.players()
                .filter { !it.isSelf() && local.distanceTo(it) <= searchRadius }
                .minByOrNull { local.distanceTo(it) }
                ?.also { lockedTargetUuid = it.uuid() }
        } else {
            world.entityByUuid(lockedTargetUuid)?.asPlayer()
        }

        if (target == null) {
            aimRotation = null
            control.sneaking(false)
            return@on
        }

        val distanceToTarget = local.distanceTo(target)
        if (distanceToTarget > approachRadius) {
            aimRotation = smoothCameraRotation(rotations.lookAt(target))
            rotations.apply(aimRotation!!, RotationOptions.DEFAULT.strongCorrection(true))
            control.sneaking(false)
            return@on
        }

        val targetLook = target.rotation().direction()
        var targetFront = Vec(targetLook.x(), 0.0, targetLook.z()).normalize()
        if (targetFront.squaredLength() == 0.0) targetFront = Vec(0.0, 0.0, 1.0)

        val away = local.position().subtract(target.position())
        var horizontalAway = Vec(away.x(), 0.0, away.z()).normalize()
        if (horizontalAway.squaredLength() == 0.0) horizontalAway = targetFront
        val awayPoint = local.eyePosition().add(horizontalAway.multiply(3.0))
        val awayYaw = rotations.lookAt(awayPoint).yawDegrees()
        aimRotation = smoothCameraRotation(Rotation(awayYaw, 85.0f))
        rotations.apply(aimRotation!!, RotationOptions.DEFAULT.strongCorrection(true))
        control.sneaking(crouching)
    }
}

on < MoveInputEvent > { input ->
    whenInGame {
        val local = player
        val target = if (lockedTargetUuid.isEmpty()) {
            world.players()
                .filter { !it.isSelf() && local.distanceTo(it) <= searchRadius }
                .minByOrNull { local.distanceTo(it) }
                ?.also { lockedTargetUuid = it.uuid() }
        } else {
            world.entityByUuid(lockedTargetUuid)?.asPlayer()
        }

        if (target == null) {
            input.forward(false)
            input.backward(false)
            input.left(false)
            input.right(false)
            input.sprint(false)
            input.sneak(false)
            return@on
        }

        val distanceToTarget = local.distanceTo(target)
        if (distanceToTarget > approachRadius) {
            val toTarget = target.position().subtract(local.position())
            var approachDirection = Vec(toTarget.x(), 0.0, toTarget.z()).normalize()
            if (approachDirection.squaredLength() == 0.0) approachDirection = Vec(0.0, 0.0, 1.0)
            val currentLook = local.rotation().direction()
            var forwardDirection = Vec(currentLook.x(), 0.0, currentLook.z()).normalize()
            if (forwardDirection.squaredLength() == 0.0) forwardDirection = approachDirection
            val rightDirection = Vec(-forwardDirection.z(), 0.0, forwardDirection.x())
            val forwardAmount = approachDirection.dot(forwardDirection)
            val sideAmount = approachDirection.dot(rightDirection)
            if (forwardAmount * forwardAmount >= sideAmount * sideAmount) {
                input.forward(forwardAmount > 0.0)
                input.backward(forwardAmount < 0.0)
                input.left(false)
                input.right(false)
            } else {
                input.forward(false)
                input.backward(false)
                input.left(sideAmount < 0.0)
                input.right(sideAmount > 0.0)
            }
            input.jump(false)
            input.sprint(true)
            input.sneak(false)
            return@on
        }

        if (destination == null) {
            input.forward(false)
            input.backward(false)
            input.left(false)
            input.right(false)
            input.sprint(false)
            input.sneak(crouching)
            return@on
        }

        val reached = local.distanceTo(destination!!) <= pointReachDistance
        if (reached || phase == Phase.ACQUIRE) {
            input.forward(false)
            input.backward(false)
            input.left(false)
            input.right(false)
        } else {
            val targetDelta = local.position().subtract(target.position())
            var forwardDirection = Vec(targetDelta.x(), 0.0, targetDelta.z()).normalize()
            if (forwardDirection.squaredLength() == 0.0) {
                val targetLook = target.rotation().direction()
                forwardDirection = Vec(targetLook.x(), 0.0, targetLook.z()).normalize()
            }
            if (forwardDirection.squaredLength() == 0.0) forwardDirection = Vec(0.0, 0.0, 1.0)
            val rightDirection = Vec(-forwardDirection.z(), 0.0, forwardDirection.x())
            val desiredDirection = destination!!.subtract(local.position()).normalize()
            val forwardAmount = desiredDirection.dot(forwardDirection)
            val sideAmount = desiredDirection.dot(rightDirection)
            if (forwardAmount * forwardAmount >= sideAmount * sideAmount) {
                input.forward(forwardAmount > 0.0)
                input.backward(forwardAmount < 0.0)
                input.left(false)
                input.right(false)
            } else {
                input.forward(false)
                input.backward(false)
                input.left(sideAmount < 0.0)
                input.right(sideAmount > 0.0)
            }
        }

        input.jump(false)
        input.sprint(false)
        input.sneak(crouching)
    }
}

on < Render3DEvent > { e ->
    whenInGame {
        if (!showPointVisualization) return@on
        val local = player
        val target = if (lockedTargetUuid.isEmpty()) {
            world.players()
                .filter { !it.isSelf() && local.distanceTo(it) <= searchRadius }
                .minByOrNull { local.distanceTo(it) }
        } else {
            world.entityByUuid(lockedTargetUuid)?.asPlayer()
        }
        val point = destination ?: return@on
        val radius = pointVisualizationRadius
        val y = point.y() + 0.01
        val a = Vec(point.x() - radius, y, point.z() - radius)
        val b = Vec(point.x() + radius, y, point.z() - radius)
        val c = Vec(point.x() + radius, y, point.z() + radius)
        val d = Vec(point.x() - radius, y, point.z() + radius)
        e.render().line(a, b, pointVisualizationColor, true)
        e.render().line(b, c, pointVisualizationColor, true)
        e.render().line(c, d, pointVisualizationColor, true)
        e.render().line(d, a, pointVisualizationColor, true)
    }
}

on < ClientTickEvent > {
    whenInGame {
        val local = player
        val target = if (lockedTargetUuid.isEmpty()) {
            world.players()
                .filter { !it.isSelf() && local.distanceTo(it) <= searchRadius }
                .minByOrNull { local.distanceTo(it) }
                ?.also { lockedTargetUuid = it.uuid() }
        } else {
            world.entityByUuid(lockedTargetUuid)?.asPlayer()
        }

        if (target == null) return@on

        val distanceToTarget = local.distanceTo(target)
        if (distanceToTarget > approachRadius) {
            destination = null
            phase = Phase.ACQUIRE
            crouching = false
            crouchAccumulator = 0.0
            legToggleDone = false
            return@on
        }

        val targetLook = target.rotation().direction()
        var targetFront = Vec(targetLook.x(), 0.0, targetLook.z()).normalize()
        if (targetFront.squaredLength() == 0.0) targetFront = Vec(0.0, 0.0, 1.0)

        val blockX = kotlin.math.floor(target.x()).toInt()
        val blockZ = kotlin.math.floor(target.z()).toInt()
        var scanY = kotlin.math.floor(target.y()).toInt() - 1
        var groundY = target.y()
        while (scanY >= -64) {
            val block = world.block(blockX, scanY, blockZ)
            if (block.blocksMovement() && block.fullCube()) {
                groundY = scanY + 1.0
                break
            }
            scanY--
        }

        val groundedTarget = Vec(target.x(), groundY, target.z())
        val currentFrontPoint = groundedTarget.add(targetFront.multiply(frontOffset))
        val towardLocal = local.position().subtract(groundedTarget)
        var targetBufferDirection = Vec(towardLocal.x(), 0.0, towardLocal.z()).normalize()
        if (targetBufferDirection.squaredLength() == 0.0) targetBufferDirection = targetFront
        val currentTargetPoint = groundedTarget.add(targetBufferDirection.multiply(targetBuffer))

        if (phase == Phase.ACQUIRE) {
            destination = currentFrontPoint
            phase = Phase.WALK_TO_FRONT
            crouching = false
            crouchAccumulator = 0.0
            legToggleDone = false
            return@on
        }

        if (phase == Phase.WALK_TO_FRONT || phase == Phase.WALK_TO_NEW_FRONT) {
            destination = currentFrontPoint
        } else if (phase == Phase.WALK_TO_TARGET) {
            destination = currentTargetPoint
        }

        val point = destination ?: return@on
        val reached = local.distanceTo(point) <= pointReachDistance

        if ((phase == Phase.WALK_TO_FRONT || phase == Phase.WALK_TO_NEW_FRONT) && reached) {
            destination = currentTargetPoint
            phase = Phase.WALK_TO_TARGET
            crouching = true
            crouchAccumulator = 0.0
            legToggleDone = false
            return@on
        }

        if (phase == Phase.WALK_TO_TARGET && reached) {
            destination = currentFrontPoint
            phase = Phase.WALK_TO_NEW_FRONT
            crouching = true
            crouchAccumulator = 0.0
            legToggleDone = false
            return@on
        }

        if ((phase == Phase.WALK_TO_TARGET || phase == Phase.WALK_TO_NEW_FRONT) && !legToggleDone) {
            crouchAccumulator += crouchTogglesPerSecond / 20.0
            if (crouchAccumulator >= 1.0) {
                crouchAccumulator -= 1.0
                crouching = false
                legToggleDone = true
            }
        }
    }
}

onDisable {
    control.sneaking(false)
    phase = Phase.ACQUIRE
    lockedTargetUuid = ""
    destination = null
    crouching = false
    crouchAccumulator = 0.0
    legToggleDone = false
    aimRotation = null
}
