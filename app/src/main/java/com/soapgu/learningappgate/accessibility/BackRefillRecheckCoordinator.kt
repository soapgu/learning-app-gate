package com.soapgu.learningappgate.accessibility

/** 延迟精准补发复检的纯状态协调器，由服务负责实际调度和发送 BACK。 */
class BackRefillRecheckCoordinator {
    private var pendingFromClass: String? = null

    var latestTargetClass: String? = null
        private set

    val hasPendingRecheck: Boolean
        get() = pendingFromClass != null

    fun observeTargetClass(className: String?) {
        latestTargetClass = className
    }

    /** 后续变化只更新最新页面，原始基线保持不变，直到复检被消费或取消。 */
    fun schedule(fromClass: String) {
        if (pendingFromClass == null) {
            pendingFromClass = fromClass
        }
    }

    fun consumeRecheck(stillTarget: Boolean): BackRefillRecheckDecision {
        val fromClass = pendingFromClass
        val latestClass = latestTargetClass
        pendingFromClass = null
        return if (stillTarget && fromClass != null && latestClass != null && latestClass != fromClass) {
            BackRefillRecheckDecision.Refill(fromClass, latestClass)
        } else {
            BackRefillRecheckDecision.Cancel(
                baselineClass = latestClass.takeIf { it == fromClass },
            )
        }
    }

    fun cancel() {
        pendingFromClass = null
    }
}

sealed interface BackRefillRecheckDecision {
    data class Refill(val fromClass: String, val toClass: String) : BackRefillRecheckDecision

    /** 页面回到原基线时提交该基线；其他取消场景保持现有基线。 */
    data class Cancel(val baselineClass: String?) : BackRefillRecheckDecision
}
