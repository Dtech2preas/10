package com.jonas.x24

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import java.util.UUID

class AdminActivity : AppCompatActivity() {

    private val MASTER_PASSWORD = "D-TECH_services"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        val etMasterPassword = findViewById<EditText>(R.id.etMasterPassword)
        val btnGenerateCode = findViewById<Button>(R.id.btnGenerateCode)
        val tvGeneratedCode = findViewById<TextView>(R.id.tvGeneratedCode)

        btnGenerateCode.setOnClickListener {
            val password = etMasterPassword.text.toString()

            if (password != MASTER_PASSWORD) {
                Toast.makeText(this, "Invalid Master Password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val newCode = UUID.randomUUID().toString().substring(0, 8)

            val dbRef = FirebaseDatabase.getInstance().getReference("access_codes").child(newCode)

            val codeData = hashMapOf(
                "secret" to MASTER_PASSWORD,
                "monitor_claimed" to false,
                "be_monitored_claimed" to false
            )

            dbRef.setValue(codeData).addOnSuccessListener {
                tvGeneratedCode.text = "Code Generated:\n$newCode"
                Toast.makeText(this, "Code Generated Successfully", Toast.LENGTH_SHORT).show()
            }.addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
