package com.kabashi.iptv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kabashi.iptv.BuildConfig
import com.kabashi.iptv.data.Credentials
import com.kabashi.iptv.data.SecureCredentialStore
import com.kabashi.iptv.data.XtreamClient
import com.kabashi.iptv.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var store: SecureCredentialStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureCredentialStore(this)

        if (store.load() != null) {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.providerText.text = "Provider: ${BuildConfig.DEFAULT_SERVER_URL.removePrefix("http://").removePrefix("https://")}"
        binding.loginButton.setOnClickListener { signIn() }
    }

    private fun signIn() {
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()

        if (username.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Enter your username and password.", Toast.LENGTH_LONG).show()
            return
        }

        val credentials = Credentials(
            serverUrl = BuildConfig.DEFAULT_SERVER_URL.trimEnd('/'),
            username = username,
            password = password
        )
        setLoading(true)
        lifecycleScope.launch {
            XtreamClient(credentials).authenticate()
                .onSuccess {
                    store.save(credentials)
                    startActivity(Intent(this@LoginActivity, DashboardActivity::class.java))
                    finish()
                }
                .onFailure {
                    setLoading(false)
                    Toast.makeText(
                        this@LoginActivity,
                        it.message ?: "Login failed.",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.loginButton.isEnabled = !loading
        binding.usernameInput.isEnabled = !loading
        binding.passwordInput.isEnabled = !loading
    }
}
