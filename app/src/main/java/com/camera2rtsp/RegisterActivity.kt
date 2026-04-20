package com.camera2rtsp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.camera2rtsp.auth.AuthResult
import com.camera2rtsp.auth.LicenseRepository
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var editFullName: EditText
    private lateinit var editEmail: EditText
    private lateinit var editPassword: EditText
    private lateinit var editPasswordConfirm: EditText
    private lateinit var btnRegister: Button
    private lateinit var btnGoLogin: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        editFullName        = findViewById(R.id.editFullName)
        editEmail           = findViewById(R.id.editEmail)
        editPassword        = findViewById(R.id.editPassword)
        editPasswordConfirm = findViewById(R.id.editPasswordConfirm)
        btnRegister         = findViewById(R.id.btnRegister)
        btnGoLogin          = findViewById(R.id.btnGoLogin)
        progressBar         = findViewById(R.id.progressBar)

        btnRegister.setOnClickListener { doRegister() }

        btnGoLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun doRegister() {
        val fullName = editFullName.text.toString().trim()
        val email    = editEmail.text.toString().trim()
        val password = editPassword.text.toString()
        val confirm  = editPasswordConfirm.text.toString()

        if (fullName.isEmpty()) {
            toast("Informe seu nome completo")
            editFullName.requestFocus()
            return
        }
        if (email.isEmpty() || password.isEmpty()) {
            toast("Preencha todos os campos")
            return
        }
        if (password != confirm) {
            toast("As senhas nao coincidem")
            return
        }
        if (password.length < 6) {
            toast("Senha deve ter pelo menos 6 caracteres")
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            when (val result = LicenseRepository.register(email, password, fullName, applicationContext)) {
                is AuthResult.Success -> {
                    setLoading(false)
                    startActivity(Intent(this@RegisterActivity, MainActivity::class.java))
                    finishAffinity()
                }
                is AuthResult.Error -> {
                    setLoading(false)
                    toast(result.message)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnRegister.isEnabled  = !loading
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
