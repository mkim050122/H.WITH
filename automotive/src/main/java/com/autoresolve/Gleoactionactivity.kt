package com.autoresolve

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gleo AI가 음성 명령을 해석해서 앱 기능을 호출할 때 들어오는 화면 없는 입구입니다.
 *
 * 역할 나눔: Gleo는 대화와 말하기를, H.WITH는 증상 분류·안전 안내·접수·센터 연결을 맡습니다.
 * Gleo가 고장을 진단하거나 확정하지 않습니다. 이 앱이 돌려준 결과(message, data)를 풀어서 말할 뿐입니다.
 *
 * REPORT_VEHICLE_PROBLEM
 *   증상 분류(SymptomTriage) → 센터 미전달 접수 초안 저장 → 진행 중 접수로 등록 → 콜백만 보냄.
 *   화면은 열지 않습니다. 정보가 부족하면 접수를 만들지 않고 asking + missingField로 돌려줍니다.
 * FIND_SERVICE_CENTER
 *   진행 중 접수가 "정확히 하나"일 때만 그 접수로 센터 화면을 엽니다.
 *   여러 개이거나, 차량이 바뀌었거나, 부위가 맞지 않거나, 없으면 임의로 고르지 않고 asking으로 돌려줍니다.
 *   (여러 개·차량 변경·부위 불일치일 때는 기존 "내 정비 접수" 화면을 열어 사용자가 직접 고르게 합니다.)
 * RECOMMEND_NEXT_CAR
 *   기존과 같습니다.
 *
 * 콜백은 어떤 경우에도 한 번만 보냅니다. 처리는 전부 로컬이라 몇 ms 안에 끝나고, 화면을 여는 것은 콜백 뒤입니다.
 * Gleo 쪽은 7초 안에 응답이 없으면 동작을 취소합니다.
 *
 * 로컬에서 Gleo 없이 시험하려면 README 맨 아래의 adb 명령을 쓰세요.
 */
class GleoActionActivity : Activity() {

    companion object {
        private const val TAG = "AutoResolveGleo"
        private const val EXTRA_ACTION_PARAM = "ACTION_PARAM"
        private const val RESPONSE_ACTION =
            "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        private const val SOURCE = "gleo"
        private const val MAX_POSSIBLE_AREAS = 4
    }

    private var responded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action.orEmpty()
        val params = parseParams(intent)

        Log.d(TAG, "action=$action paramKeys=${params.keys().asSequence().toList()}")

        try {
            when (action) {
                "REPORT_VEHICLE_PROBLEM" -> reportProblem(action, params)
                "FIND_SERVICE_CENTER" -> findCenter(action, params)
                "RECOMMEND_NEXT_CAR" -> nextCar(action)
                else -> respond(action, "fail", "This request is not supported.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gleo action failed", e)
            respond(action, "fail", "Something went wrong while handling the request.")
        } finally {
            // 어떤 경로로든 콜백이 빠지지 않게 합니다.
            if (!responded) {
                respond(action, "fail", "The request could not be completed.")
            }
            finish()
        }
    }

    // ---------------------------------------------------------------
    // REPORT_VEHICLE_PROBLEM

    private fun reportProblem(action: String, params: JSONObject) {
        val symptom = params.optString("symptom").trim()
        val hint = partOrNull(params.optString("part"))
        val profile = VehicleProfileStore(this).load()

        val triage = SymptomTriage.analyze(symptom, hint, isEvOrPhev(profile))

        Log.d(
            TAG,
            "triage urgency=${triage.urgency.wire} part=${triage.part.name} " +
                    "missing=${triage.missingField} matched=${triage.matched} symptomLen=${symptom.length}"
        )

        // 정보가 부족하면 접수를 만들지 않습니다. 임의로 증상을 채우지 않습니다.
        if (triage.needsMoreInfo) {
            respond(
                action, "asking",
                "More detail about the problem is needed before it can be assessed.",
                dataOf(
                    "missingField" to triage.missingField,
                    "whatIsMissing" to triage.headline,
                    "askFor" to triage.advice,
                    "diagnosisConfirmed" to false
                )
            )
            return
        }

        val request = buildRequest(profile, triage, symptom)

        // 센터로 전달하지 않은 초안(SAVED)으로만 저장합니다.
        ServiceRequestStore(this).save(request)
        ActiveConsultStore(this).register(request.id, profile, SOURCE)

        val message = when (triage.urgency) {
            Urgency.STOP_NOW ->
                "The symptom was classified as potentially unsafe and saved as a draft that has not been sent to any service center."
            Urgency.SELF_CHECK ->
                "The symptom was saved as a draft and the app offers a guided self-check for it on screen."
            else ->
                "The symptom was saved as a draft and a professional inspection is recommended because the cause is not confirmed."
        }

        respond(
            action, "success", message,
            dataOf(
                "requestId" to request.id,
                "urgency" to triage.urgency.wire,
                "part" to triage.part.label,
                "headline" to triage.headline,
                "advice" to triage.advice,
                "possibleAreas" to possibleAreas(triage),
                "diagnosisConfirmed" to false,
                "savedAs" to "draft_not_sent_to_any_service_center",
                "vehicleRegistered" to (profile != null),
                "serviceCentersCanBeShown" to true
            )
        )
        // 화면은 열지 않습니다. 운전자가 원하면 Gleo가 FIND_SERVICE_CENTER를 호출합니다.
    }

    // ---------------------------------------------------------------
    // FIND_SERVICE_CENTER

    private fun findCenter(action: String, params: JSONObject) {
        val hint = partOrNull(params.optString("part"))
        val sort = if (params.optString("sortBy").equals("fastest", true)) {
            CenterSort.FASTEST
        } else {
            CenterSort.NEAREST
        }

        val profile = VehicleProfileStore(this).load()

        when (val resolution = ActiveConsultStore(this).resolve(profile)) {
            is ActiveResolution.Single -> {
                val request = ServiceRequestStore(this).find(resolution.entry.requestId)

                when {
                    request == null ->
                        askForProblem(action)

                    // 말한 부위가 저장된 접수와 다르면 다른 문제일 수 있어서 사용자가 확인해야 합니다.
                    hint != null &&
                            request.part != PartCategory.OTHER &&
                            hint != request.part -> {
                        respond(
                            action, "asking",
                            "The requested part does not match the saved request, so the driver must confirm which request to use on the screen.",
                            dataOf(
                                "reason" to "part_mismatch",
                                "requestedPart" to hint.label,
                                "savedPart" to request.part.label
                            )
                        )
                        openHistory()
                    }

                    else -> {
                        // 위험도는 부위가 아니라 저장된 증상으로 정합니다. (브레이크라는 이유만으로 긴급이 되지 않음)
                        val urgent = SymptomTriage.analyze(
                            request.symptom,
                            request.part,
                            isEvOrPhev(profile)
                        ).isSafetyFirst

                        // 콜백을 먼저 보내고 화면을 엽니다.
                        respond(
                            action, "success",
                            "Sample service centers are shown on the screen and the driver must choose one and confirm the request there.",
                            dataOf(
                                "requestId" to request.id,
                                "part" to request.part.label,
                                "order" to sort.name.lowercase(),
                                "orderMeaning" to orderMeaning(sort),
                                "dataSource" to "demo_sample_data",
                                "realAvailabilityKnown" to false,
                                "partsStockKnown" to false,
                                "reservationConfirmed" to false,
                                "navigationAvailable" to NaviLauncher.hasAnyVerifiedPlace(),
                                "urgentSafetyFirst" to urgent
                            )
                        )
                        openCenters(request.id, sort, urgent)
                    }
                }
            }

            is ActiveResolution.Ambiguous -> {
                respond(
                    action, "asking",
                    "More than one saved request could match, so the driver must choose one on the screen.",
                    dataOf("reason" to "multiple_requests", "count" to resolution.entries.size)
                )
                openHistory()
            }

            is ActiveResolution.VehicleChanged -> {
                respond(
                    action, "asking",
                    "The registered vehicle differs from the one used in the earlier consultation, so the driver must confirm the request on the screen.",
                    dataOf("reason" to "vehicle_changed", "count" to resolution.entries.size)
                )
                openHistory()
            }

            ActiveResolution.None -> askForProblem(action)
        }
    }

    /** 이어받을 상담이 없을 때. 기존 증상을 지어내지 않고 다시 알려 달라고 합니다. */
    private fun askForProblem(action: String) {
        respond(
            action, "asking",
            "The problem description is needed before service centers can be shown.",
            dataOf(
                "missingField" to "symptom",
                "reason" to "no_active_request",
                "askFor" to "어떤 증상인지 먼저 알려 주세요."
            )
        )
    }

    private fun orderMeaning(sort: CenterSort): String =
        if (sort == CenterSort.FASTEST) {
            "shortest estimated travel time plus sample waiting time; the waiting time is demo data, not real availability"
        } else {
            "shortest distance from the driver among sample centers"
        }

    // ---------------------------------------------------------------
    // RECOMMEND_NEXT_CAR (동작은 기존과 동일, 콜백을 먼저 보냄)

    private fun nextCar(action: String) {
        respond(
            action, "success",
            "The next car recommendation is open. The driver only needs to answer a few remaining questions."
        )

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_NEXT_CAR)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    // ---------------------------------------------------------------

    private fun buildRequest(
        profile: VehicleProfile?,
        triage: TriageResult,
        symptom: String
    ): ServiceRequest {
        val vehicle = if (profile == null) {
            "차량 미등록"
        } else {
            val brand = VehicleCatalog.brands[profile.manufacturer] ?: profile.manufacturer
            "$brand ${profile.model} · ${profile.year}년식 · " +
                    VehicleCatalog.getPowertrainLabel(profile.powertrain)
        }

        return ServiceRequest(
            id = newRequestId(),
            createdAt = System.currentTimeMillis(),
            vehicle = vehicle,
            part = triage.part,
            symptom = symptom,
            diagnosisSummary = "Gleo 음성 상담 · H.WITH 예비 분류: ${triage.headline}\n" +
                    "${triage.advice}\n" +
                    "참고용이며 고장을 확정하지 않아요.",
            transcript = "",
            centerId = null,
            centerName = null,
            status = RequestStatus.SAVED
        )
    }

    /** 질문 트리가 있는 증상일 때만, 그 트리가 다루는 확인 영역을 "가능성"으로 알려 줍니다. 확정이 아닙니다. */
    private fun possibleAreas(triage: TriageResult): JSONArray? {
        val issue = triage.selfCheckIssue ?: return null
        val flow = DiagnosisEngine.getFlow(issue) ?: return null

        val titles = flow.results.values
            .filter { it.id != "unknown" && it.id != "uncertain" }
            .map { it.title }
            .take(MAX_POSSIBLE_AREAS)

        return if (titles.isEmpty()) null else JSONArray(titles)
    }

    private fun isEvOrPhev(profile: VehicleProfile?): Boolean =
        profile != null && profile.powertrain in listOf(VehicleCatalog.EV, VehicleCatalog.PHEV)

    private fun openCenters(requestId: String, sort: CenterSort, urgent: Boolean) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_REQUEST_ID, requestId)
                putExtra(MainActivity.EXTRA_SORT, sort.name)
                putExtra(MainActivity.EXTRA_URGENT, urgent)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    private fun openHistory() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_REQUEST_HISTORY)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    private fun partOrNull(value: String): PartCategory? {
        val v = value.trim()
        if (v.isEmpty()) return null
        return PartCategory.values().firstOrNull { it.name.equals(v, ignoreCase = true) }
    }

    // ACTION_PARAM은 JSON 문자열입니다. 따옴표로 한 번 더 감싸 오는 경우도 처리합니다.
    private fun parseParams(source: Intent?): JSONObject {
        val raw = source?.getStringExtra(EXTRA_ACTION_PARAM)?.trim().orEmpty()
        if (raw.isEmpty()) return JSONObject()

        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            try {
                JSONObject(raw.trim('"').replace("\\\"", "\""))
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse ACTION_PARAM", e)
                JSONObject()
            }
        }
    }

    /**
     * Callback Schema의 data: "key-value 객체의 JSON 배열을 문자열로 만든 값"입니다.
     * 예) [{"requestId":"AR-...","urgency":"inspect_soon"}]
     */
    private fun dataOf(vararg pairs: Pair<String, Any?>): String {
        val obj = JSONObject()
        pairs.forEach { (key, value) -> if (value != null) obj.put(key, value) }
        return JSONArray().put(obj).toString()
    }

    /** Gleo AI에 결과를 알립니다. 한 번만 보냅니다. Gleo는 7초 안에 응답이 없으면 동작을 취소합니다. */
    private fun respond(
        functionName: String,
        status: String,
        message: String,
        data: String? = null
    ) {
        if (responded) return
        responded = true

        runCatching {
            val json = JSONObject().apply {
                put("functionName", functionName)
                put("status", status)
                put("message", message)
                data?.let { put("data", it) }
            }

            sendBroadcast(
                Intent(RESPONSE_ACTION).apply {
                    putExtra("packageName", packageName)
                    putExtra("response", json.toString())
                }
            )

            Log.d(TAG, "callback sent: $json")
        }.onFailure {
            Log.e(TAG, "callback failed", it)
        }
    }
}
