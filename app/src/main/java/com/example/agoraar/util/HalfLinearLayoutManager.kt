package com.example.agoraar.util

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HalfLinearLayoutManager(
    context: Context?,
    @RecyclerView.Orientation orientation: Int,
    reverseLayout: Boolean
) : LinearLayoutManager(context, orientation, reverseLayout) {
    override fun checkLayoutParams(lp: RecyclerView.LayoutParams?): Boolean {
        lp?.height = height / 2
        return true
    }
}