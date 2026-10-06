/* Personal playback: no location data is saved in browser storage. */
"use strict";
const texts = {
    en: {
        license: "Map library licence",
        moving: "Moving time",
        blind: "Without trusted GPS",
        routeLength: "Planned route",
        reroutes: "Reroutes",
        maxUncertainty: "Maximum uncertainty",
        arrived: "Arrived",
        notArrived: "Not arrived",
        legend: "Red: recorded journey · Blue: planned route · Circle: position uncertainty",
        GPS_SUSPECT: "Uncertain GPS",
        DR_NET: "Dead reckoning with network",
        DR_OBD: "Dead reckoning with vehicle speed",
        DR_STOPPED: "Stopped",
        NONE: "No position source",
        account: "My account",
        title: "My trips",
        language: "Language",
        private:
            "Your personal archive. Upload completed trips from History in the app.",
        from: "From",
        to: "To",
        filter: "Filter",
        previous: "Previous",
        next: "Next",
        play: "Play",
        pause: "Pause",
        restart: "Restart",
        fit: "Fit trip",
        speed: "Speed",
        timeline: "Timeline",
        download: "Download playback",
        delete: "Delete server copy",
        confirm:
            "Delete this trip from the server? The recording on your phone will remain.",
        mapPrivacy:
            "The map provider, OpenFreeMap, receives map area requests and your IP address. Your trip document stays on this server.",
        mapError:
            "The map is unavailable. Playback controls and statistics still work.",
        error: "Could not load trip history. Sign in with your own account and try again.",
        empty: "No uploaded trips in this period.",
        incomplete: "Incomplete recording: some data may be missing.",
        gap: "No recorded position in this interval.",
        usage: "Archive usage",
        trips: "trips",
        CAR: "Driving",
        FOOT: "Walking",
        distance: "Distance",
        duration: "Duration",
        uncertainty: "Uncertainty",
        GPS: "GPS",
        DR: "Dead reckoning",
        CELL: "Cell towers",
        HYBRID: "Combined position",
        unknown: "Recorded estimate",
        source: "Position source",
    },
    uk: {
        license: "Ліцензія бібліотеки карти",
        moving: "Час у русі",
        blind: "Без надійного GPS",
        routeLength: "Запланований маршрут",
        reroutes: "Перебудови маршруту",
        maxUncertainty: "Найбільша похибка",
        arrived: "Прибули",
        notArrived: "Не прибули",
        legend: "Червоний: записана поїздка · Синій: запланований маршрут · Коло: похибка позиції",
        GPS_SUSPECT: "Сумнівний GPS",
        DR_NET: "Обчислена позиція з мережею",
        DR_OBD: "Обчислена позиція зі швидкістю авто",
        DR_STOPPED: "Зупинка",
        NONE: "Немає джерела позиції",
        account: "Мій обліковий запис",
        title: "Мої поїздки",
        language: "Мова",
        private:
            "Ваш особистий архів. Завантажуйте завершені поїздки з історії в застосунку.",
        from: "Від",
        to: "До",
        filter: "Фільтрувати",
        previous: "Назад",
        next: "Далі",
        play: "Відтворити",
        pause: "Пауза",
        restart: "Спочатку",
        fit: "Показати всю поїздку",
        speed: "Швидкість",
        timeline: "Часова шкала",
        download: "Завантажити відтворення",
        delete: "Видалити копію із сервера",
        confirm:
            "Видалити цю поїздку із сервера? Запис на телефоні залишиться.",
        mapPrivacy:
            "Постачальник карти OpenFreeMap отримує запити ділянок карти та вашу IP-адресу. Документ поїздки залишається на цьому сервері.",
        mapError: "Карта недоступна. Відтворення та статистика працюють.",
        error: "Не вдалося завантажити історію. Увійдіть у власний обліковий запис і спробуйте ще раз.",
        empty: "За цей період немає завантажених поїздок.",
        incomplete: "Неповний запис: частина даних може бути відсутня.",
        gap: "У цьому проміжку немає записаної позиції.",
        usage: "Використання архіву",
        trips: "поїздок",
        CAR: "Автомобілем",
        FOOT: "Пішки",
        distance: "Відстань",
        duration: "Тривалість",
        uncertainty: "Похибка",
        GPS: "GPS",
        DR: "Обчислена позиція",
        CELL: "Мобільні вежі",
        HYBRID: "Комбінована позиція",
        unknown: "Записана оцінка",
        source: "Джерело позиції",
    },
    ru: {
        license: "Лицензия библиотеки карты",
        moving: "Время в движении",
        blind: "Без надёжного GPS",
        routeLength: "Запланированный маршрут",
        reroutes: "Перестроения маршрута",
        maxUncertainty: "Наибольшая погрешность",
        arrived: "Прибыли",
        notArrived: "Не прибыли",
        legend: "Красный: записанная поездка · Синий: запланированный маршрут · Круг: погрешность позиции",
        GPS_SUSPECT: "Сомнительный GPS",
        DR_NET: "Расчётная позиция с сетью",
        DR_OBD: "Расчётная позиция со скоростью авто",
        DR_STOPPED: "Остановка",
        NONE: "Нет источника позиции",
        account: "Моя учётная запись",
        title: "Мои поездки",
        language: "Язык",
        private:
            "Ваш личный архив. Загружайте завершённые поездки из истории в приложении.",
        from: "С",
        to: "По",
        filter: "Показать",
        previous: "Назад",
        next: "Далее",
        play: "Воспроизвести",
        pause: "Пауза",
        restart: "Сначала",
        fit: "Показать всю поездку",
        speed: "Скорость",
        timeline: "Шкала времени",
        download: "Скачать воспроизведение",
        delete: "Удалить копию с сервера",
        confirm: "Удалить эту поездку с сервера? Запись на телефоне останется.",
        mapPrivacy:
            "Поставщик карты OpenFreeMap получает запросы участков карты и ваш IP-адрес. Документ поездки остаётся на этом сервере.",
        mapError: "Карта недоступна. Воспроизведение и статистика работают.",
        error: "Не удалось загрузить историю. Войдите в собственную учётную запись и повторите попытку.",
        empty: "За этот период нет загруженных поездок.",
        incomplete: "Неполная запись: часть данных может отсутствовать.",
        gap: "В этом промежутке нет записанной позиции.",
        usage: "Использование архива",
        trips: "поездок",
        CAR: "На автомобиле",
        FOOT: "Пешком",
        distance: "Расстояние",
        duration: "Длительность",
        uncertainty: "Погрешность",
        GPS: "GPS",
        DR: "Расчётная позиция",
        CELL: "Мобильные вышки",
        HYBRID: "Комбинированная позиция",
        unknown: "Записанная оценка",
        source: "Источник позиции",
    },
};

/** Binary search returns the last real sample, without inventing motion through missing data. */
function sampleAt(positions, time) {
    let low = 0,
        high = positions.length;
    while (low < high) {
        const middle = (low + high) >>> 1;
        if (positions[middle].time_ms <= time) low = middle + 1;
        else high = middle;
    }
    if (!low) return null;
    const point = positions[low - 1],
        next = positions[low];
    if (
        time > point.time_ms &&
        next &&
        (next.segment !== point.segment || next.time_ms - point.time_ms > 5000)
    )
        return null;
    return point;
}
function trackSegments(positions) {
    const lines = [];
    let line = [];
    let previous = null;
    for (const point of positions) {
        if (
            previous &&
            (point.segment !== previous.segment ||
                point.time_ms - previous.time_ms > 5000)
        ) {
            if (line.length > 1) lines.push(line);
            line = [];
        }
        line.push([point.lon, point.lat]);
        previous = point;
    }
    if (line.length > 1) lines.push(line);
    return lines;
}
if (typeof module !== "undefined") module.exports = { sampleAt, trackSegments };
if (typeof document !== "undefined")
    start().catch(() => {
        document.getElementById("status").textContent =
            texts[document.documentElement.lang]?.error || texts.en.error;
    });
async function start() {
    const element = (id) => document.getElementById(id);
    let language = (navigator.language || "en").slice(0, 2);
    if (!texts[language]) language = "en";
    let dictionary = texts[language],
        offset = 0,
        documentData = null,
        map = null,
        marker = null,
        playing = false,
        lastFrame = 0,
        clock = 0,
        mapReady = false,
        animation = null;
    const translate = (key) => dictionary[key] || dictionary.unknown;
    function localize() {
        dictionary = texts[language];
        document.documentElement.lang = language;
        element("language").value = language;
        document
            .querySelectorAll("[data-text]")
            .forEach(
                (node) => (node.textContent = translate(node.dataset.text)),
            );
        element("map").setAttribute("aria-label", translate("title"));
        if (element("map-error").textContent)
            element("map-error").textContent = translate("mapError");
        if (documentData) {
            describe();
            render();
        }
    }
    element("language").onchange = () => {
        language = element("language").value;
        localize();
        if (!documentData) history().catch(fail);
    };
    localize();
    const csrf = document.querySelector('meta[name="csrf-token"]').content;
    async function request(path, method = "GET") {
        const response = await fetch(path, {
            method,
            credentials: "same-origin",
            cache: "no-store",
            headers: method === "GET" ? {} : { "x-csrf-token": csrf },
        });
        if (!response.ok) throw new Error("request failed");
        return response.status === 204 ? null : response.json();
    }
    function fail() {
        element("status").textContent = translate("error");
    }
    function statistics(summary) {
        return `${translate(summary.mode)} · ${translate("distance")}: ${(summary.distance_m / 1000).toLocaleString(language, { maximumFractionDigits: 1 })} km · ${translate("duration")}: ${Math.round(summary.duration_s / 60)} min`;
    }
    async function history() {
        const params = new URLSearchParams({ offset });
        if (element("from").value)
            params.set(
                "from_ms",
                new Date(element("from").value + "T00:00:00").getTime(),
            );
        if (element("to").value)
            params.set(
                "to_ms",
                new Date(element("to").value + "T23:59:59.999").getTime(),
            );
        const data = await request("/v1/trips?" + params);
        element("usage").textContent =
            `${translate("usage")}: ${(data.bytes / 1048576).toFixed(1)} / ${(data.limits.account_bytes / 1048576).toFixed(0)} MiB · ${data.count} / ${data.limits.account_trips} ${translate("trips")}`;
        element("trips").replaceChildren();
        for (const trip of data.trips) {
            const item = document.createElement("li"),
                link = document.createElement("a");
            link.href = "/trips/" + encodeURIComponent(trip.id);
            link.textContent = new Date(
                trip.data.summary.start_ms,
            ).toLocaleString(language);
            const detail = document.createElement("p");
            detail.textContent =
                statistics(trip.data.summary) +
                (trip.data.incomplete ? " · " + translate("incomplete") : "");
            item.append(link, detail);
            element("trips").append(item);
        }
        element("status").textContent = data.trips.length
            ? ""
            : translate("empty");
        element("previous").disabled = offset === 0;
        element("next").disabled = !data.more;
    }
    element("filter").onsubmit = (event) => {
        event.preventDefault();
        offset = 0;
        history().catch(fail);
    };
    element("previous").onclick = () => {
        offset = Math.max(0, offset - 50);
        history().catch(fail);
    };
    element("next").onclick = () => {
        offset += 50;
        history().catch(fail);
    };
    const id = location.pathname.split("/")[2];
    if (!id) {
        await history();
        return;
    }
    element("history").hidden = true;
    element("player").hidden = false;
    documentData = await request("/v1/trips/" + encodeURIComponent(id));
    const positions = documentData.positions,
        end = positions[positions.length - 1].time_ms;
    element("seek").max = end;
    element("download").href = "/v1/trips/" + encodeURIComponent(id);
    function describe() {
        const summary = documentData.summary;
        element("date").textContent = new Date(summary.start_ms).toLocaleString(
            language,
        );
        element("summary").textContent =
            statistics(summary) +
            " · " +
            translate(summary.arrived ? "arrived" : "notArrived");
        element("incomplete").textContent = documentData.incomplete
            ? translate("incomplete")
            : "";
        element("trip-stats").replaceChildren();
        const unit = (value, name) =>
            new Intl.NumberFormat(language, {
                style: "unit",
                unit: name,
                maximumFractionDigits: 1,
            }).format(value);
        for (const [key, value] of [
            ["moving", unit(summary.moving_s / 60, "minute")],
            [
                "blind",
                unit(summary.blind_s / 60, "minute") +
                    " · " +
                    unit(summary.blind_m, "meter"),
            ],
            ["routeLength", unit(summary.route_length_m / 1000, "kilometer")],
            ["maxUncertainty", unit(summary.max_uncertainty_m, "meter")],
            ["reroutes", summary.reroutes],
        ]) {
            const group = document.createElement("div"),
                label = document.createElement("dt"),
                content = document.createElement("dd");
            label.textContent = translate(key);
            content.textContent = value;
            group.append(label, content);
            element("trip-stats").append(group);
        }
    }
    describe();
    const geo = (type, coordinates) => ({
        type: "Feature",
        properties: {},
        geometry: { type, coordinates },
    });
    function circle(point) {
        const coordinates = [];
        const angular = point.uncertainty_m / 6371000,
            lat = (point.lat * Math.PI) / 180,
            lon = (point.lon * Math.PI) / 180;
        for (let index = 0; index <= 64; index++) {
            const bearing = (index * 2 * Math.PI) / 64;
            const latitude = Math.asin(
                Math.sin(lat) * Math.cos(angular) +
                    Math.cos(lat) * Math.sin(angular) * Math.cos(bearing),
            );
            const longitude =
                lon +
                Math.atan2(
                    Math.sin(bearing) * Math.sin(angular) * Math.cos(lat),
                    Math.cos(angular) - Math.sin(lat) * Math.sin(latitude),
                );
            coordinates.push([
                (longitude * 180) / Math.PI,
                (latitude * 180) / Math.PI,
            ]);
        }
        return geo("Polygon", [coordinates]);
    }
    function fit() {
        if (!map) return;
        const bounds = new maplibregl.LngLatBounds();
        for (const p of positions) bounds.extend([p.lon, p.lat]);
        map.fitBounds(bounds, { padding: 40, maxZoom: 16, duration: 0 });
    }
    function render() {
        const point = sampleAt(positions, clock);
        element("seek").value = clock;
        element("play").textContent = translate(playing ? "pause" : "play");
        element("gap").textContent = point ? "" : translate("gap");
        element("position").textContent =
            `${(clock / 1000).toFixed(1)} s / ${(end / 1000).toFixed(1)} s` +
            (point
                ? ` · ${translate("source")}: ${translate(point.source)} · ${translate("uncertainty")}: ${Math.round(point.uncertainty_m)} m`
                : "");
        if (!mapReady) return;
        marker.getElement().hidden = !point;
        map.getSource("uncertainty").setData(
            point ? circle(point) : { type: "FeatureCollection", features: [] },
        );
        if (point) marker.setLngLat([point.lon, point.lat]);
        let route = null;
        for (const candidate of documentData.routes) {
            if (candidate.time_ms > clock) break;
            if (point && candidate.segment === point.segment) route = candidate;
        }
        map.getSource("route").setData(
            route
                ? geo("LineString", route.points)
                : { type: "FeatureCollection", features: [] },
        );
    }
    try {
        maplibregl.setWorkerUrl("/trip-map/maplibre-gl-csp-worker.js");
        map = new maplibregl.Map({
            container: "map",
            style: "https://tiles.openfreemap.org/styles/liberty",
            center: [30.5, 50.4],
            zoom: 6,
        });
        map.on("error", () => {
            element("map-error").textContent = translate("mapError");
        });
        map.on("load", () => {
            map.addSource("track", {
                type: "geojson",
                data: geo("MultiLineString", trackSegments(positions)),
            });
            map.addLayer({
                id: "track",
                type: "line",
                source: "track",
                paint: { "line-color": "#d74938", "line-width": 4 },
            });
            map.addSource("route", {
                type: "geojson",
                data: { type: "FeatureCollection", features: [] },
            });
            map.addLayer({
                id: "route",
                type: "line",
                source: "route",
                paint: {
                    "line-color": "#3078c5",
                    "line-width": 3,
                    "line-opacity": 0.65,
                },
            });
            map.addSource("uncertainty", {
                type: "geojson",
                data: { type: "FeatureCollection", features: [] },
            });
            map.addLayer({
                id: "uncertainty",
                type: "fill",
                source: "uncertainty",
                paint: { "fill-color": "#da9332", "fill-opacity": 0.25 },
            });
            marker = new maplibregl.Marker()
                .setLngLat([positions[0].lon, positions[0].lat])
                .addTo(map);
            mapReady = true;
            fit();
            render();
        });
    } catch (_) {
        element("map-error").textContent = translate("mapError");
    }
    function frame(now) {
        if (!playing) return;
        if (lastFrame)
            clock = Math.min(
                end,
                clock + (now - lastFrame) * Number(element("speed").value),
            );
        lastFrame = now;
        if (clock >= end) playing = false;
        render();
        if (playing) animation = requestAnimationFrame(frame);
    }
    element("play").onclick = () => {
        if (animation !== null) cancelAnimationFrame(animation);
        playing = !playing;
        if (playing) {
            if (clock >= end) clock = 0;
            lastFrame = 0;
            animation = requestAnimationFrame(frame);
        }
        render();
    };
    element("restart").onclick = () => {
        clock = 0;
        lastFrame = 0;
        render();
    };
    element("seek").oninput = () => {
        clock = Number(element("seek").value);
        lastFrame = 0;
        render();
    };
    element("fit").onclick = fit;
    element("delete").onclick = async () => {
        if (!confirm(translate("confirm"))) return;
        try {
            await request("/v1/trips/" + encodeURIComponent(id), "DELETE");
            location.assign("/trips");
        } catch (_) {
            fail();
        }
    };
    document.addEventListener("visibilitychange", () => {
        if (document.hidden) {
            if (animation !== null) cancelAnimationFrame(animation);
            playing = false;
            lastFrame = 0;
            render();
        }
    });
    render();
}
