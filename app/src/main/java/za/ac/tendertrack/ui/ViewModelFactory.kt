package za.ac.tendertrack.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Lets a screen build a ViewModel that needs a constructor argument (a tender
 * id, a flag id) without pulling in a dependency-injection framework.
 *
 * Usage: viewModel(factory = viewModelFactory { TenderFormViewModel(id) })
 */
fun <VM : ViewModel> viewModelFactory(builder: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = builder() as T
    }
