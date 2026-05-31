package com.example.bruceir

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private val devices: List<Device>,
    private val listener: OnDeviceClickListener
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    interface OnDeviceClickListener {
        fun onDeviceClick(device: Device)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvInfo: TextView = view.findViewById(R.id.tvDeviceInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = devices.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val device = devices[position]
        holder.tvName.text = device.name
        holder.tvInfo.text = "${device.commands.size} komend"
        holder.itemView.setOnClickListener { listener.onDeviceClick(device) }
    }
}
