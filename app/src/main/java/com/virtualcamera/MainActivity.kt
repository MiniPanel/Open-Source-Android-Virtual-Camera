package com.virtualcamera

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!checkPermissions()) {
            requestRuntimePermissions()
        }
        
        val serviceIntent = Intent(this, VirtualCameraService::class.java)
        startService(serviceIntent)
    }
    
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, VirtualCameraService::class.java).apply {
                action = VirtualCameraService.ACTION_START
                putExtra(VirtualCameraService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(VirtualCameraService.EXTRA_PROJECTION_DATA, result.data)
            }
            startService(serviceIntent)
            Toast.makeText(this, "Virtual camera started", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Projection permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startVirtualCamera() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }
    
    private fun stopVirtualCamera() {
        val intent = Intent(this, VirtualCameraService::class.java).apply {
            action = VirtualCameraService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "Virtual camera stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun checkPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else true
        return camera && notif && storage
    }
    
    private fun requestRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.READ_MEDIA_VIDEO
        }
        requestPermissions(perms.toTypedArray(), 1001)
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startVirtualCamera()
        }
    }
}
