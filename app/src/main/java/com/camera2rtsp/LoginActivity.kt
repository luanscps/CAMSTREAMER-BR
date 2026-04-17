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

class LoginActivity : AppCompatActivity() {

    private lateinit var editEmail: EditText
    private lateinit var editPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnGoRegister: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        editEmail    = findViewById(R.id.editEmail)
        editPassword = findViewById(R.id.editPassword)
        btnLogin     = findViewById(R.id.btnLogin)
        btnGoRegister = findViewById(R.id.btnGoRegister)
        progressBar  = findViewById(R.id.progressBar)

        btnLogin.setOnClickListener { doLogin() }

        btnGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun doLogin() {
        val email    = editEmail.text.toString().trim()
        val password = editPassword.text.toString()

        if (email.isEmpty() || password.isEmpty()) {
            toast("Preencha e-mail e senha")
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            when (val result = LicenseRepository.login(email, password, applicationContext)) {
                is AuthResult.Success -> {
                    setLoading(false)
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
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
        btnLogin.isEnabled     = !loading
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
