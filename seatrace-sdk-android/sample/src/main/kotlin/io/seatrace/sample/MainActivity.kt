package io.seatrace.sample

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.seatrace.sample.databinding.ActivityMainBinding
import io.seatrace.sdk.SeaTraceClient
import io.seatrace.sdk.SeaTraceConfig
import io.seatrace.sdk.connection.ConnectionState
import io.seatrace.sdk.connection.ReconnectPolicy
import io.seatrace.sdk.debug.LogLevel
import io.seatrace.sdk.debug.MessageDirection
import io.seatrace.sdk.model.VesselUpdate
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Sample activity demonstrating SeaTrace SDK usage.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var client: SeaTraceClient
    private lateinit var adapter: VesselAdapter
    private lateinit var logAdapter: LogAdapter

    private val vessels = mutableMapOf<Long, VesselUpdate>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerViews()
        setupClient()
        setupButtons()

        // Start connection automatically
        connect()
    }

    private fun setupRecyclerViews() {
        adapter = VesselAdapter()
        binding.vesselsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.vesselsRecyclerView.adapter = adapter

        logAdapter = LogAdapter()
        binding.logRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.logRecyclerView.adapter = logAdapter
    }

    private fun setupClient() {
        // Configure the client
        val config = SeaTraceConfig.Builder()
            .endpoint("ws://asgard.fritz.box:8080/realtime")
            .reconnectPolicy(ReconnectPolicy.Default)
            .debugMode(true)
            .logLevel(LogLevel.DEBUG)
            .build()

        client = SeaTraceClient(config)

        // Set up raw message listener for debug panel
        client.setRawMessageListener { direction, message ->
            runOnUiThread {
                val prefix = if (direction == MessageDirection.INBOUND) "<<" else ">>"
                addLog("$prefix ${message.take(100)}...")
            }
        }

        // Observe connection state
        lifecycleScope.launch {
            client.connectionState.collectLatest { state ->
                updateConnectionStatus(state)
            }
        }

        // Observe vessel updates
        lifecycleScope.launch {
            client.vesselsFlow.collectLatest { update ->
                vessels[update.position.mmsi] = update
                adapter.submitList(vessels.values.toList().sortedByDescending { it.event.timestamp })
            }
        }

        // Observe errors
        lifecycleScope.launch {
            client.errorsFlow.collectLatest { error ->
                addLog("ERROR: ${error.message}")
            }
        }
    }

    private fun setupButtons() {
        binding.connectButton.setOnClickListener {
            if (client.isConnected.value) {
                disconnect()
            } else {
                connect()
            }
        }

        binding.clearButton.setOnClickListener {
            vessels.clear()
            adapter.submitList(emptyList())
            logAdapter.submitList(emptyList())
        }
    }

    private fun connect() {
        lifecycleScope.launch {
            addLog("Connecting...")
            client.connect()

            // Subscribe to all vessel positions (wildcard)
            client.subscribeAll()
            addLog("Subscribed to all events")
        }
    }

    private fun disconnect() {
        client.disconnect()
        addLog("Disconnected")
    }

    private fun updateConnectionStatus(state: ConnectionState) {
        val (text, color) = when (state) {
            is ConnectionState.Disconnected -> "Disconnected" to android.R.color.holo_red_dark
            is ConnectionState.Connecting -> "Connecting..." to android.R.color.holo_orange_dark
            is ConnectionState.Connected -> "Connected" to android.R.color.holo_green_dark
            is ConnectionState.Reconnecting -> "Reconnecting (${state.attempt})..." to android.R.color.holo_orange_dark
            is ConnectionState.Failed -> "Failed" to android.R.color.holo_red_dark
        }

        binding.statusText.text = text
        binding.statusText.setTextColor(getColor(color))
        binding.connectButton.text = if (state.isConnected) "Disconnect" else "Connect"
    }

    private fun addLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] $message"
        val currentLogs = logAdapter.currentList.toMutableList()
        currentLogs.add(0, logEntry)
        if (currentLogs.size > 100) {
            currentLogs.removeLast()
        }
        logAdapter.submitList(currentLogs)
    }

    override fun onDestroy() {
        super.onDestroy()
        client.close()
    }
}

/**
 * Adapter for displaying vessel positions.
 */
class VesselAdapter : ListAdapter<VesselUpdate, VesselAdapter.ViewHolder>(VesselDiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val mmsiText: TextView = view.findViewById(android.R.id.text1)
        val detailsText: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val update = getItem(position)
        val vessel = update.position

        holder.mmsiText.text = "MMSI: ${vessel.mmsi}"

        val details = buildString {
            append("Pos: %.4f, %.4f".format(vessel.lat, vessel.lon))
            vessel.sog?.let { append(" | SOG: %.1f kn".format(it)) }
            vessel.cog?.let { append(" | COG: %.0f°".format(it)) }
        }
        holder.detailsText.text = details
    }

    class VesselDiffCallback : DiffUtil.ItemCallback<VesselUpdate>() {
        override fun areItemsTheSame(oldItem: VesselUpdate, newItem: VesselUpdate): Boolean {
            return oldItem.position.mmsi == newItem.position.mmsi
        }

        override fun areContentsTheSame(oldItem: VesselUpdate, newItem: VesselUpdate): Boolean {
            return oldItem == newItem
        }
    }
}

/**
 * Simple adapter for log messages.
 */
class LogAdapter : ListAdapter<String, LogAdapter.ViewHolder>(LogDiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.textView.text = getItem(position)
        holder.textView.textSize = 10f
    }

    class LogDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    }
}
