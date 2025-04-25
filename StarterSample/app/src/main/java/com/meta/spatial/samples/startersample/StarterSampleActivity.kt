/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.meta.spatial.samples.startersample

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebView
import android.widget.Button
import android.widget.SeekBar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import com.meta.spatial.runtime.LayerConfig
import com.meta.spatial.runtime.NetworkedAssetLoader
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.vr.VRFeature
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch



class StarterSampleActivity : AppSystemActivity() {

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "StarterSampleActivity"
    }

    private var gltfxEntity: Entity? = null
    private var skyboxEntity: Entity? = null
    private val activityScope = CoroutineScope(Dispatchers.Main)
    private var videoWebView: WebView? = null

    override fun registerFeatures(): List<SpatialFeature> {
        val features = mutableListOf<SpatialFeature>(VRFeature(this))
        if (BuildConfig.DEBUG) {
            features.add(CastInputForwardFeature(this))
        }
        return features
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set immersive mode.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    )
        }

        // Request CAMERA permission if not granted.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        }

        // Initialize NetworkedAssetLoader.
        NetworkedAssetLoader.init(
            File(applicationContext.cacheDir.canonicalPath),
            OkHttpAssetFetcher()
        )

        // Load the scene composition.
        loadGLXF().invokeOnCompletion {
            val composition = glXFManager.getGLXFInfo("example_key_name")
            val environmentEntity: Entity? = composition.getNodeByName("Environment")?.entity
            val environmentMesh = environmentEntity?.getComponent<Mesh>()
            environmentMesh?.defaultShaderOverride = SceneMaterial.UNLIT_SHADER
            environmentEntity?.setComponent(environmentMesh!!)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Camera permission granted.")
            } else {
                Log.e(TAG, "Camera permission denied. Passthrough mode may not work.")
            }
        }
    }

    override fun onSceneReady() {
        super.onSceneReady()

        // Set reference space.
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)

        // Configure lighting environment.
        scene.setLightingEnvironment(
            ambientColor = Vector3(0f),
            sunColor = Vector3(7.0f, 7.0f, 7.0f),
            sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
            environmentIntensity = 0.3f
        )
        scene.updateIBLEnvironment("environment.env")
        scene.setViewOrigin(0.0f, 0.3f, 5.6f, 180.0f)

        // Create and store the skybox entity.
        skyboxEntity = Entity.create(
            listOf(
                Mesh(Uri.parse("mesh://skybox")),
                Material().apply {
                    baseTextureAndroidResourceId = R.drawable.island_skybox
                    unlit = true
                },
                Transform(Pose(Vector3(x = 0f, y = 0f, z = 0f)))
            )
        )

        // Enable passthrough.
        scene.enablePassthrough(true)
        Log.d(TAG, "Passthrough mode enabled in onSceneReady().")
    }

    override fun registerPanels(): List<PanelRegistration> {
        return listOf(
            // 🎥 Video Panel Registration using the local HTML file.
            PanelRegistration(R.layout.video_panel) {
                config {
                    themeResourceId = R.style.PanelAppThemeTransparent
                    includeGlass = false
                    enableTransparent = true
                    layerConfig = LayerConfig()
                }
                panel {
                    videoWebView = rootView?.findViewById(R.id.web_view)
                    videoWebView?.settings?.apply {
                        javaScriptEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                    }
                    videoWebView?.loadUrl("file:///android_asset/video_player.html")
                }
            },
            // 🎛️ Control Panel Registration.
            PanelRegistration(R.layout.control_panel) {
                config {
                    themeResourceId = R.style.PanelAppThemeTransparent
                    includeGlass = false
                    enableTransparent = true
                    layerConfig = LayerConfig()
                }
                panel {
                    val pauseBtn = rootView?.findViewById<Button>(R.id.btn_pause_play)
                    val volumeSlider = rootView?.findViewById<SeekBar>(R.id.volume_slider)
                    var isPaused = false

                    pauseBtn?.setOnClickListener {
                        val jsCommand = if (isPaused) "playMyVideo();" else "pauseMyVideo();"
                        Log.d("VideoControl", "Executing JS: $jsCommand")
                        videoWebView?.evaluateJavascript(jsCommand, null)
                        pauseBtn.text = if (isPaused) "Pause" else "Play"
                        isPaused = !isPaused
                    }

                    volumeSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            setSystemVolume(progress)
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                    })
                }
            },
            // 🔆 Lighting Panel Registration: Changes ambient lighting and skybox.
            PanelRegistration(R.layout.lighting_panel) {
                config {
                    themeResourceId = R.style.PanelAppThemeTransparent
                    includeGlass = false
                    enableTransparent = true
                    layerConfig = LayerConfig()
                }
                panel {
                    // Get references to the new lighting mode buttons.

                    val btnBeach = rootView?.findViewById<Button>(R.id.btn_skybox_beach)
                    val btnAurora = rootView?.findViewById<Button>(R.id.btn_skybox_aurora)
                    val btnMountain = rootView?.findViewById<Button>(R.id.btn_skybox_mountain)
                    val btnForest = rootView?.findViewById<Button>(R.id.btn_skybox_forest)

                    // Set ambient light to white (pure white light).
                    

                    // Skybox selection button listeners.
                    btnBeach?.setOnClickListener { updateSkybox(R.drawable.skybox_beach) }
                    btnAurora?.setOnClickListener { updateSkybox(R.drawable.island_skybox) }
                    btnMountain?.setOnClickListener { updateSkybox(R.drawable.skydome) }
                    btnForest?.setOnClickListener { updateSkybox(R.drawable.skybox_forest) }
                }
            }
        )
    }

    // Helper to adjust system volume.
    private fun setSystemVolume(percent: Int) {
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val volumeLevel = (percent / 100.0 * maxVolume).toInt()
        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volumeLevel, 0)
    }

    // Helper to update the skybox texture.
    private fun updateSkybox(resourceId: Int) {
        skyboxEntity?.let { entity ->
            val material = entity.getComponent<Material>() ?: Material()
            material.baseTextureAndroidResourceId = resourceId
            entity.setComponent(material)
            Log.d(TAG, "Skybox updated to resource id: $resourceId")
        }
    }



    // Asynchronously load the GLXF scene composition.
    private fun loadGLXF(): Job {
        gltfxEntity = Entity.create()
        return activityScope.launch {
            glXFManager.inflateGLXF(
                Uri.parse("apk:///scenes/Composition.glxf"),
                rootEntity = gltfxEntity!!,
                keyName = "example_key_name"
            )
        }
    }
}
