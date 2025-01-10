/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.sample.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.asTextOrNull
import com.google.ai.client.generativeai.type.content
import com.google.ai.sample.MainActivity
import com.google.ai.sample.feature.trip.Trip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.google.ai.sample.R
import com.google.ai.sample.feature.chat.ChipType
import com.google.ai.sample.feature.trip.TripLeg
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader

class ChatViewModel(
    generativeModel: GenerativeModel,
) : ViewModel() {
    private val chat = generativeModel.startChat(
        history = listOf(
            content(role = "model") { text("I found these trips in last 6 months. Choose one to create a blog.") }
        )
    )

    private var prompt: String = ""

    private var currIdx = MutableStateFlow<Int>(-1)
    private val trip = MutableStateFlow<Trip?>(null)
    private val currentJourney = MutableStateFlow<List<TripLeg>?>(null)

    init {
        startTripCollection()
        startIterationCollection()
    }

    private fun startIterationCollection() {
        viewModelScope.launch {
            currIdx.collect { idx ->
                if (idx == -1) {
                    return@collect
                }
                currentJourney.value?.let {
                    if(it.size <= idx) {
                        // send prompt to model
                        //sendMessage(prompt)
                        currIdx.value = -1
                        return@let
                    }

                    //val currentLeg = it[idx]
                    val currNearBy = it.getOrElse (idx) {
                        // return when idx exceeds array
                        return@let
                    }
                    pushNearByMessage(currNearBy)
                }
            }
        }
    }

    private fun pushNearByMessage(leg: TripLeg) {
        val prompt = buildString {
            append("Have you visited any of these places?")
            if(leg.nearbyPlaces.size == 0) {
                // add leg to prompt
                prompt += leg
                currIdx.value = currIdx.value + 1
            }
            leg.nearbyPlaces.forEachIndexed { index, place ->
                append("${currIdx}. ${place.nodeName}")
            }
        }

        val chips = leg.nearbyPlaces.mapIndexed { currIdx, place ->
            ChipDetails(
                id = "id_${leg.location}_$currIdx",
                type = ChipType.Trip,
                text = place.nodeName
            )
        }

        _uiState.value.addMessage(
            ChatMessage(
                text = prompt,
                participant = Participant.MODEL,
                isPending = false,
                chips = chips,
                isMultiselect = true
            )
        )
    }

    private fun startTripCollection() {
        viewModelScope.launch {
            trip.collect { t ->
                t?.journey?.let {
                    currentJourney.value = it
                    currIdx.emit(0)
                }
            }
        }
    }

    private val _uiState: MutableStateFlow<ChatUiState> =
        MutableStateFlow(ChatUiState(chat.history.map { content ->
            // Map the initial messages
            ChatMessage(
                text = content.parts.first().asTextOrNull() ?: "",
                participant = if (content.role == "user") Participant.USER else Participant.MODEL,
                isPending = false,
                chips = listOf(
                    ChipDetails(id = "vietnam", type = ChipType.Trip, text = "Vietnam"),
                    ChipDetails(id = "leh", type = ChipType.Scenic, text = "Leh"),
                    ChipDetails(id = "pondicherry", type = ChipType.Trip, text = "Pondicherry")
                )
            )
        }))
    val uiState: StateFlow<ChatUiState> =
        _uiState.asStateFlow()

    fun sendMessage(userMessage: String) {
        // Add a pending message
        _uiState.value.addMessage(
            ChatMessage(
                text = userMessage,
                participant = Participant.USER,
                isPending = true
            )
        )

        viewModelScope.launch {
            try {
                when (userMessage) {
                    "Leh" -> {
                        _uiState.value.addMessage(
                            ChatMessage(
                                text = "Fetching your Leh trip data...",
                                participant = Participant.MODEL,
                                isPending = false
                            )
                        )
                    }
                }
                val response = chat.sendMessage(userMessage)
                _uiState.value.replaceLastPendingMessage()

                response.text?.let { modelResponse ->
                    _uiState.value.addMessage(
                        ChatMessage(
                            text = modelResponse,
                            participant = Participant.MODEL,
                            isPending = false,
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.value.replaceLastPendingMessage()
                _uiState.value.addMessage(
                    ChatMessage(
                        text = e.localizedMessage,
                        participant = Participant.ERROR
                    )
                )
            }
        }
    }

    fun handleChipSelection(chip: ChipDetails) {
        when (chip.type) {
            is ChipType.Trip -> handleTrip(chip)
            ChipType.Food -> handleTrip(chip)
            ChipType.Scenic -> handleTrip(chip)
        }

    }

    private fun handleTrip(details: ChipDetails) {
        val trip = fetchTrip(
            when (details.text) {
                "Leh" -> R.raw.leh
                else -> throw Exception("Blah")
            }
        )
        startTripParsing(trip)

    }

    private fun startTripParsing(trip: Trip?) {
        viewModelScope.launch { this@ChatViewModel.trip.emit(trip) }
    }

    private fun fetchTrip(resId: Int): Trip? {
        val jsonString = try {
            val inputStream = MainActivity.context?.resources?.openRawResource(resId)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val stringBuilder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stringBuilder.append(line)
            }
            reader.close()
            stringBuilder.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        return try {
            Gson().fromJson(jsonString, Trip::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            println("Error fetching data")
            null
        }
    }

    fun handleSubmit(details: ChatMessage) {
        // handle submit update chatMessage filter non selected data.
        details.chips = details.chips.filter {
            it.enabled == true
        }

        currIdx.value = currIdx.value + 1

    }

}