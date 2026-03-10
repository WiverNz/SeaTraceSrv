package io.seatrace.android.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.seatrace.android.R
import io.seatrace.android.databinding.BottomSheetLayersBinding
import io.seatrace.android.map.LayerVisibility

/**
 * Bottom sheet for toggling map layers.
 *
 * Communicate changes back through [onLayerChanged] callback rather than a
 * shared ViewModel reference so the sheet stays independent.
 */
class LayersBottomSheet(
    private val initial: LayerVisibility,
    private val onLayerChanged: (nautical: Boolean, ships: Boolean) -> Unit,
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLayersBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetLayersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.switchNautical.isChecked = initial.nauticalOverlay
        binding.switchShips.isChecked   = initial.ships

        val notify = {
            onLayerChanged(
                binding.switchNautical.isChecked,
                binding.switchShips.isChecked,
            )
        }

        binding.switchNautical.setOnCheckedChangeListener { _, _ -> notify() }
        binding.switchShips.setOnCheckedChangeListener    { _, _ -> notify() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "LayersBottomSheet"
    }
}
