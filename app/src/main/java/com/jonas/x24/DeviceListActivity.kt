package com.jonas.x24

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class DeviceListActivity : AppCompatActivity() {

    private lateinit var sessionKey: String
    private lateinit var rvDevices: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var adapter: DeviceAdapter
    private val deviceList = mutableListOf<DeviceInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)

        sessionKey = intent.getStringExtra("SESSION_KEY") ?: return finish()

        rvDevices = findViewById(R.id.rvDevices)
        tvEmptyState = findViewById(R.id.tvEmptyState)

        rvDevices.layoutManager = LinearLayoutManager(this)
        adapter = DeviceAdapter(deviceList) { deviceId ->
            val intent = Intent(this, MonitorActivity::class.java)
            intent.putExtra("SESSION_KEY", sessionKey)
            intent.putExtra("DEVICE_ID", deviceId)
            startActivity(intent)
        }
        rvDevices.adapter = adapter

        loadDevices()
    }

    private fun loadDevices() {
        val devicesRef = FirebaseDatabase.getInstance().getReference("sessions").child(sessionKey).child("devices")
        devicesRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                deviceList.clear()
                for (deviceSnapshot in snapshot.children) {
                    val id = deviceSnapshot.key ?: continue
                    val name = deviceSnapshot.child("info").child("name").getValue(String::class.java) ?: "Unknown Device"
                    val manufacturer = deviceSnapshot.child("info").child("manufacturer").getValue(String::class.java) ?: ""
                    val isOnline = deviceSnapshot.child("state").child("isOnline").getValue(Boolean::class.java) ?: false

                    val fullName = if (manufacturer.isNotEmpty()) "$manufacturer $name" else name
                    deviceList.add(DeviceInfo(id, fullName, isOnline))
                }

                if (deviceList.isEmpty()) {
                    tvEmptyState.visibility = View.VISIBLE
                    rvDevices.visibility = View.GONE
                } else {
                    tvEmptyState.visibility = View.GONE
                    rvDevices.visibility = View.VISIBLE
                    adapter.notifyDataSetChanged()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // handle error
            }
        })
    }

    data class DeviceInfo(val id: String, val name: String, val isOnline: Boolean)

    class DeviceAdapter(private val devices: List<DeviceInfo>, private val onClick: (String) -> Unit) :
        RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
            val tvStatus: TextView = view.findViewById(R.id.tvStatus)
            val tvDeviceId: TextView = view.findViewById(R.id.tvDeviceId)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.tvDeviceName.text = device.name
            holder.tvDeviceId.text = "ID: ${device.id}"

            if (device.isOnline) {
                holder.tvStatus.text = "Online"
                holder.tvStatus.setTextColor(Color.parseColor("#388E3C")) // Dark green text
                holder.tvStatus.setBackgroundColor(Color.parseColor("#C8E6C9")) // Light green bg
            } else {
                holder.tvStatus.text = "Offline"
                holder.tvStatus.setTextColor(Color.parseColor("#C62828")) // Dark red text
                holder.tvStatus.setBackgroundColor(Color.parseColor("#FFCDD2")) // Light red bg
            }

            holder.itemView.setOnClickListener {
                onClick(device.id)
            }
        }

        override fun getItemCount() = devices.size
    }
}
