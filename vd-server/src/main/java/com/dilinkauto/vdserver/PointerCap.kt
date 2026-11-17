package com.dilinkauto.vdserver

/**
 * 活跃指针表的上限策略（audit S-09）。
 *
 * [TouchInjector] 的 `activePointers` 只有 UP 会删除，而 pointerId 来自 wire 上
 * 未校验的 Int：丢一个 UP 就永久多一个幽灵指针，恶意/损坏帧可以直接插任意 id。
 * `propsPool`/`coordsPool` 长 [TouchInjector.MAX_POINTERS] 且按插入序下标索引，
 * 表一旦超过上限，`propsPool[i]` 就抛 ArrayIndexOutOfBoundsException —— 被
 * `injectTouch` 的空 catch 吞掉后，后续每一帧都在写表之后、发布事件之前抛异常，
 * 整个会话的触摸半死不活且无法自愈。
 *
 * 这里是纯逻辑（不碰 MotionEvent/反射），所以可以单独锁行为：新指针进来时淘汰
 * "最早插入且不是本指针"的条目，把表钳回 [maxPointers]。
 */
internal object PointerCap {

    /**
     * 把 [pointerId] 的 [coords] 写入 [map]；表已满时按插入序淘汰最早的其它指针。
     *
     * 永不淘汰 [pointerId] 自身 —— 否则刚插入的指针可能被随即剔除，导致后续
     * "找不到本指针下标"而丢弃这一帧（DOWN 帧被丢 = 幽灵按下）。
     *
     * [onDrop] 每淘汰一个指针回调一次，供调用方记日志（审计要求可见，不许静默）。
     */
    fun put(
        map: MutableMap<Int, FloatArray>,
        pointerId: Int,
        coords: FloatArray,
        maxPointers: Int,
        onDrop: (Int) -> Unit
    ) {
        while (map.size >= maxPointers && pointerId !in map) {
            val oldest = map.keys.firstOrNull { it != pointerId } ?: break
            map.remove(oldest)
            onDrop(oldest)
        }
        map[pointerId] = coords
    }
}
