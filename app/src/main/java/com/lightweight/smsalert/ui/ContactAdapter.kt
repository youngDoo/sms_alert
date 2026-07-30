package com.lightweight.smsalert.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lightweight.smsalert.databinding.ItemSpecialContactBinding
import com.lightweight.smsalert.model.SpecialContact

class ContactAdapter(
    private val onDeleteClick: (SpecialContact) -> Unit
) : ListAdapter<SpecialContact, ContactAdapter.ViewHolder>(ContactDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSpecialContactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSpecialContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: SpecialContact) {
            binding.tvAvatar.text = contact.name.firstOrNull()?.toString()
                ?: contact.phoneNumber.takeLast(1)

            binding.tvContactName.text = contact.name.ifEmpty { "未命名" }
            binding.tvContactPhone.text = contact.phoneNumber

            binding.tvRingtoneDesc.text = when (contact.ringtoneUri) {
                "alarm" -> "闹钟音"
                "ringtone" -> "电话铃"
                "notification" -> "提示音"
                else -> "系统默认"
            }

            binding.btnDelete.setOnClickListener {
                onDeleteClick(contact)
            }
        }
    }

    private class ContactDiffCallback : DiffUtil.ItemCallback<SpecialContact>() {
        override fun areItemsTheSame(oldItem: SpecialContact, newItem: SpecialContact): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SpecialContact, newItem: SpecialContact): Boolean {
            return oldItem == newItem
        }
    }
}
