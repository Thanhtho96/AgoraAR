package com.example.agoraar

import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class VideoAdapter : ListAdapter<ViewData, VideoAdapter.ViewHolder>(ViewData.ViewDataDiffUtil) {
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val videoPreview: FrameLayout = itemView.findViewById(R.id.viewPreview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // Here I need to check if FrameLayout already contain a SurfaceView
        // I need to remove it and add it again
        // Because I call notifyItemChanged in MainActivity, it will run bindViewHolder again with local video
        getItem(position).surfaceView.also {
            val parent = it.parent
            if (parent != null) {
                (parent as FrameLayout).removeView(it)
            }
            holder.videoPreview.addView(getItem(position).surfaceView)
        }
    }
}

data class ViewData(val surfaceView: SurfaceView) {
    object ViewDataDiffUtil : DiffUtil.ItemCallback<ViewData>() {
        override fun areItemsTheSame(oldItem: ViewData, newItem: ViewData) =
            oldItem.surfaceView == newItem.surfaceView

        override fun areContentsTheSame(oldItem: ViewData, newItem: ViewData) =
            oldItem == newItem
    }
}
