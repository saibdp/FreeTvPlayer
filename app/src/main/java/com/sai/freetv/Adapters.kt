package com.sai.freetv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

data class Group(val name: String, val channels: List<Channel>)

private fun View.focusScale(hasFocus: Boolean) {
    val s = if (hasFocus) 1.04f else 1f
    animate().scaleX(s).scaleY(s).setDuration(120).start()
}

/** Left column: countries / categories. Focusing a row selects it. */
class GroupAdapter(
    private val onSelected: (Int) -> Unit,
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    var items: List<Group> = emptyList()
        set(value) {
            field = value
            selected = 0
            notifyDataSetChanged()
        }
    var selected = 0
        private set

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.groupName)
        val count: TextView = v.findViewById(R.id.groupCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val group = items[position]
        holder.name.text = group.name
        holder.count.text = group.channels.size.toString()
        holder.itemView.isActivated = position == selected
        // TV remote: moving focus onto a row selects it
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.focusScale(hasFocus)
            if (hasFocus) select(holder, v)
        }
        // Mouse / touch (phones, BlueStacks): clicking a row selects it
        holder.itemView.setOnClickListener { v -> select(holder, v) }
    }

    private fun select(holder: VH, v: View) {
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION || pos == selected) return
        val old = selected
        selected = pos
        v.isActivated = true
        notifyItemChanged(old)
        onSelected(pos)
    }
}

/** Right column: channels in the selected group. Clicking (OK on the remote) plays. */
class ChannelAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.channelNumber)
        val logo: ImageView = v.findViewById(R.id.channelLogo)
        val name: TextView = v.findViewById(R.id.channelName)
        val tag: TextView = v.findViewById(R.id.channelTag)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.number.text = (position + 1).toString()
        holder.name.text = ch.name
        holder.tag.visibility = if (ch.isExternal) View.VISIBLE else View.GONE
        holder.logo.load(ch.logo) {
            crossfade(true)
            placeholder(R.drawable.logo_placeholder)
            error(R.drawable.logo_placeholder)
            fallback(R.drawable.logo_placeholder)
        }
        holder.itemView.setOnFocusChangeListener { v, hasFocus -> v.focusScale(hasFocus) }
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
    }
}
