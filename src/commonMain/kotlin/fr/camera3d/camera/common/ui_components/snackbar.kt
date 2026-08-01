package fr.camera3d.camera.common.ui_components

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// The Context/R.string.id overload is Android-only and lives in SnackbarContextExtension.kt
// instead (same package, so existing call sites are unaffected). Sync via tools/sync-from-android.ps1.

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class SnackbarElements(val message : String, val actionLabel : String, val actionCallback : ()-> Unit = {})

/**
 * Displays a simple Snackbar with only a text and a short duration
 * @param scope :
 * @param snackbarHostState : state to use for displaying snackbar
 * @param string : text of the Snackbar
 */
fun snackbar(scope : CoroutineScope, snackbarHostState : SnackbarHostState, string : String){
    scope.launch {
        snackbarHostState.showSnackbar(string)
    }
}

/**
 * Displays a Snackbar with an action button and an infinite duration
 * @param scope :
 * @param snackbarHostState : state to use for displaying snackbar
 * @param string :
 * @param actionLabel : action button label
 * @param actionCallback : callback called when action button is pressed
 */
fun snackbar(scope : CoroutineScope, snackbarHostState : SnackbarHostState, string : String, actionLabel: String, actionCallback : ()->Unit){
    scope.launch {
        val result = snackbarHostState.showSnackbar(string, actionLabel)
        if (result == SnackbarResult.ActionPerformed){
            actionCallback()
        }
    }
}

/**
 * Displays a Snackbar with an action button and an infinite duration
 * @param scope :
 * @param snackbarHostState : state to use for displaying snackbar
 * @param snackbarElement
 */
fun snackbar(scope : CoroutineScope, snackbarHostState : SnackbarHostState, snackbarElements: SnackbarElements){
    if (snackbarElements.actionLabel == ""){
        snackbar(scope, snackbarHostState, snackbarElements.message)
    } else {
        snackbar(scope, snackbarHostState,
            snackbarElements.message,
            snackbarElements.actionLabel,
            snackbarElements.actionCallback
        )
    }
}
