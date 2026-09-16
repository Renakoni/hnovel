package indi.renakoni.nextvol.data.local.room.entity

interface Mergeable<T> {
    fun merge(new: T): T
}