package com.campusrun.sim

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import com.campusrun.sim.ui.MapScreen
import com.campusrun.sim.ui.SimulationViewModel
import org.osmdroid.config.Configuration

/** Entry point: requests location/notification permissions up front,
 *  then shows the map -> route -> pace -> cadence -> start flow.
 *  If a permission is missing, the user still sees the map and a
 *  clear error when they try to start, instead of a silent failure. */
class MainActivity : ComponentActivity() {

    private val viewModel: SimulationViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Results are checked again at start time in the ViewModel.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = "CampusRunSim/0.1.0"
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
        setContent {
            MaterialTheme {
                Surface {
                    MapScreen(viewModel = viewModel)
                }
            }
        }
    }
}
