package com.lightweight.smsalert.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lightweight.smsalert.databinding.ItemContentRuleBinding
import com.lightweight.smsalert.model.ContentRule

class ContentRuleAdapter(
    private val onDeleteClick: (ContentRule) -> Unit
) : ListAdapter<ContentRule, ContentRuleAdapter.ViewHolder>(RuleDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContentRuleBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemContentRuleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: ContentRule) {
            binding.tvRuleIcon.text = rule.name.firstOrNull()?.toString() ?: "规"
            binding.tvRuleName.text = rule.name
            binding.tvRulePattern.text = rule.pattern
            binding.tvRuleRingtone.text = when (rule.ringtoneUri) {
                "alarm" -> "闹钟音"
                "ringtone" -> "电话铃"
                "notification" -> "提示音"
                else -> "系统默认"
            }

            binding.btnDeleteRule.setOnClickListener {
                onDeleteClick(rule)
            }
        }
    }

    private class RuleDiffCallback : DiffUtil.ItemCallback<ContentRule>() {
        override fun areItemsTheSame(oldItem: ContentRule, newItem: ContentRule): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ContentRule, newItem: ContentRule): Boolean {
            return oldItem == newItem
        }
    }
}
