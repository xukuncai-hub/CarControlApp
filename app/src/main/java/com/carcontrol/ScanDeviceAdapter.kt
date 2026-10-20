package com.carcontrol

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.carcontrol.databinding.ItemScanDeviceBinding

class ScanDeviceAdapter(
    private val onClick: (BleDevice) -> Unit
) : RecyclerView.Adapter<ScanDeviceAdapter.Holder>() {

    private val items = mutableListOf<BleDevice>()

    class Holder(val binding: ItemScanDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemScanDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val device = items[position]
        val context = holder.binding.root.context
        holder.binding.tvName.text = device.name
        holder.binding.tvCarBadge.isVisible = device.isCar
        holder.binding.tvAddress.text = device.address
        holder.binding.tvRssi.text = context.getString(R.string.rssi_format, device.rssi)
        holder.binding.tvRssi.setTextColor(
            ContextCompat.getColor(
                context,
                when {
                    device.rssi >= -60 -> R.color.success
                    device.rssi >= -75 -> R.color.warning
                    else -> R.color.danger
                }
            )
        )
        holder.binding.root.setOnClickListener { onClick(device) }
        holder.binding.root.contentDescription =
            "${device.name}，${device.address}，信号强度 ${device.rssi} dBm"
    }

    @SuppressLint("NotifyDataSetChanged")
    fun upsert(device: BleDevice) {
        val index = items.indexOfFirst { it.address == device.address }
        if (index >= 0) {
            items[index] = device
        } else {
            items.add(device)
        }
        items.sortWith(compareByDescending<BleDevice> { it.isCar }.thenByDescending { it.rssi })
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = items.isEmpty()
}
