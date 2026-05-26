package com.jonas.x24

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

class FileAdapter(context: Context, private val files: List<Map<String, Any>>, private val onFetchClicked: (String) -> Unit) :
    ArrayAdapter<Map<String, Any>>(context, R.layout.item_file, files) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_file, parent, false)
        val file = files[position]

        val tvName = view.findViewById<TextView>(R.id.tvFileName)
        val tvDetails = view.findViewById<TextView>(R.id.tvFileDetails)
        val ivIcon = view.findViewById<ImageView>(R.id.ivFileIcon)
        val btnFetch = view.findViewById<Button>(R.id.btnFetchFile)

        tvName.text = file["name"] as String
        val size = (file["size"] as Number).toLong()
        val type = file["type"] as String

        if (type == "FOLDER") {
            tvDetails.text = "Directory"
            ivIcon.setImageResource(android.R.drawable.ic_menu_sort_by_size)
            btnFetch.visibility = View.GONE
        } else {
            tvDetails.text = Formatter.formatShortFileSize(context, size)
            if (type == "PICTURE") {
                ivIcon.setImageResource(android.R.drawable.ic_menu_gallery)
                btnFetch.visibility = View.VISIBLE
            } else if (type == "VIDEO") {
                ivIcon.setImageResource(android.R.drawable.ic_menu_camera)
                btnFetch.visibility = View.GONE
            } else {
                ivIcon.setImageResource(android.R.drawable.ic_menu_help)
                btnFetch.visibility = View.GONE
            }
        }

        btnFetch.setOnClickListener {
            onFetchClicked(file["path"] as String)
        }

        return view
    }
}
