package com.myra.assistant.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import com.myra.assistant.data.PrimeContact

class PrimeContactAdapter : RecyclerView.Adapter<PrimeContactAdapter.VH>() {

    private val items = mutableListOf<PrimeContact>()

    fun submit(list: List<PrimeContact>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun add(contact: PrimeContact) {
        items.add(contact)
        notifyItemInserted(items.size - 1)
    }

    fun remove(position: Int) {
        if (position !in items.indices) return
        items.removeAt(position)
        notifyItemRemoved(position)
    }

    fun current(): List<PrimeContact> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_prime_contact, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.number.text = item.number
        holder.delete.setOnClickListener { remove(holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.primeItemName)
        val number: TextView = view.findViewById(R.id.primeItemNumber)
        val delete: ImageButton = view.findViewById(R.id.primeItemDelete)
    }
}
