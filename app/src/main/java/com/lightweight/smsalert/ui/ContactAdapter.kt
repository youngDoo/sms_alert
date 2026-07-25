package com.lightweight.smsalert.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lightweight.smsalert.databinding.ItemSpecialContactBinding
import com.lightweight.smsalert.model.SpecialContact

class ContactAdapter(
    private var contacts: List<SpecialContact>,
    private val onDeleteClick: (SpecialContact) -> Unit
) : RecyclerView.Adapter<ContactAdapter.ViewHolder>() {

    fun updateContacts(newContacts: List<SpecialContact>) {
        contacts = newContacts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSpecialContactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(contacts[position])
    }

    override fun getItemCount(): Int = contacts.size

    inner class ViewHolder(private val binding: ItemSpecialContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: SpecialContact) {
            // 头像首字母
            binding.tvAvatar.text = contact.name.firstOrNull()?.toString() ?: contact.phoneNumber.takeLast(1)

            binding.tvContactName.text = contact.name.ifEmpty { "未命名" }
            binding.tvContactPhone.text = contact.phoneNumber

            val ringtoneLabel = when (contact.ringtoneUri) {
                "alarm" -> "闹钟音"
                "ringtone" -> "电话铃"
                "notification" -> "提示音"
                else -> "系统默认"
            }
            val intervalLabel = when (contact.repeatIntervalSec) {
                30 -> "30秒"
                60 -> "1分钟"
                120 -> "2分钟"
                180 -> "3分钟"
                300 -> "5分钟"
                else -> "${contact.repeatIntervalSec}秒"
            }
            binding.tvRingtoneDesc.text = "$ringtoneLabel · $intervalLabel"

            binding.btnDelete.setOnClickListener {
                onDeleteClick(contact)
            }
        }
    }
}
