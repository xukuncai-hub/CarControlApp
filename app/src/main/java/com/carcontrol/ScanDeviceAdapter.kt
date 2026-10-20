package com.carcontrol

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.carcontrol.databinding.ItemScanDeviceBinding

class ScanDeviceAdapter(
    private val onClick: (BleDevice) -> Unit
) : ListAdapter<BleDevice, ScanDeviceAdapter.Holder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BleDevice>() {
            override fun areItemsTheSame(oldItem: BleDevice, newItem: BleDevice): Boolean =
                oldItem.address == newItem.address

            override fun areContentsTheSame(oldItem: BleDevice, newItem: BleDevice): Boolean =
                oldItem == newItem
        }
    }

    class Holder(val binding: ItemScanDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).address.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemScanDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val device = getItem(position)
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
}
