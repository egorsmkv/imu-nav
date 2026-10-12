//! Desktop labels and maneuver vocabulary use the same three languages as Android.

use imu_nav_protocol::Maneuver;
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum Language {
    #[default]
    English,
    Ukrainian,
    Russian,
}

impl Language {
    pub fn text(self, key: &str) -> &'static str {
        let words = LABELS
            .iter()
            .find(|(label, _)| *label == key)
            .map_or(("?", "?", "?"), |(_, words)| *words);
        match self {
            Self::English => words.0,
            Self::Ukrainian => words.1,
            Self::Russian => words.2,
        }
    }

    pub fn maneuver(self, step: &Maneuver) -> String {
        let direction = match step.modifier.as_deref() {
            Some("left") => "left",
            Some("right") => "right",
            Some("slight left") => "slight_left",
            Some("slight right") => "slight_right",
            Some("sharp left") => "sharp_left",
            Some("sharp right") => "sharp_right",
            Some("uturn") => "uturn",
            _ => "straight",
        };
        let action = match step.kind.as_str() {
            "arrive" => self.text("arrived").into(),
            "depart" => self.text("depart").into(),
            "roundabout" | "rotary" => format!(
                "{} {}",
                self.text("roundabout"),
                step.roundabout_exit
                    .map_or_else(|| "—".into(), |exit| exit.to_string())
            ),
            "fork" => format!("{}: {}", self.text("fork"), self.text(direction)),
            "merge" | "on ramp" => format!("{}: {}", self.text("merge"), self.text(direction)),
            "off ramp" => format!("{}: {}", self.text("ramp"), self.text(direction)),
            _ => self.text(direction).into(),
        };
        if step.name.is_empty() {
            action
        } else {
            format!("{action} · {}", step.name)
        }
    }
}

// A static catalog keeps all three translations together without branching UI code.
const LABELS: &[(&str, (&str, &str, &str))] = &[
    ("address", ("Address", "Адреса", "Адрес")),
    ("port", ("Port", "Порт", "Порт")),
    ("connect", ("Connect", "Підключити", "Подключить")),
    ("disconnect", ("Disconnect", "Відключити", "Отключить")),
    ("disconnected", ("Disconnected", "Відключено", "Отключено")),
    ("connecting", ("Connecting", "Підключення", "Подключение")),
    ("connected", ("Connected", "Підключено", "Подключено")),
    (
        "retrying",
        ("Reconnecting", "Перепідключення", "Переподключение"),
    ),
    (
        "invalid",
        (
            "Invalid navigation data",
            "Некоректні дані навігації",
            "Некорректные данные навигации",
        ),
    ),
    (
        "unsupported",
        (
            "Unsupported sharing version",
            "Непідтримувана версія обміну",
            "Неподдерживаемая версия обмена",
        ),
    ),
    (
        "endpoint",
        (
            "Enter a private IPv4 address and a valid port",
            "Введіть приватну IPv4-адресу та коректний порт",
            "Введите частный IPv4-адрес и корректный порт",
        ),
    ),
    ("settings", ("Settings", "Налаштування", "Настройки")),
    ("dark", ("Dark theme", "Темна тема", "Тёмная тема")),
    ("online", ("Online", "Онлайн", "Онлайн")),
    ("offline", ("Offline", "Офлайн", "Офлайн")),
    ("map", ("Map", "Мапа", "Карта")),
    (
        "import",
        ("Open MBTiles", "Відкрити MBTiles", "Открыть MBTiles"),
    ),
    (
        "loading",
        ("Loading map", "Завантаження мапи", "Загрузка карты"),
    ),
    (
        "no_map",
        (
            "Open a raster MBTiles map for offline use",
            "Відкрийте растрову мапу MBTiles для роботи офлайн",
            "Откройте растровую карту MBTiles для работы офлайн",
        ),
    ),
    (
        "bad_map",
        (
            "Unable to open this raster map",
            "Не вдалося відкрити цю растрову мапу",
            "Не удалось открыть эту растровую карту",
        ),
    ),
    (
        "map_url",
        (
            "HTTPS XYZ tile URL",
            "HTTPS-адреса тайлів XYZ",
            "HTTPS-адрес тайлов XYZ",
        ),
    ),
    (
        "attribution",
        ("Map attribution", "Авторство мапи", "Авторство карты"),
    ),
    (
        "apply",
        (
            "Apply map settings",
            "Застосувати налаштування мапи",
            "Применить настройки карты",
        ),
    ),
    (
        "privacy",
        (
            "Online maps share the viewed area with the tile provider. Offline mode uses only your local map.",
            "Онлайн-мапи повідомляють постачальнику тайлів про ділянку перегляду. Офлайн-режим використовує лише локальну мапу.",
            "Онлайн-карты сообщают поставщику тайлов область просмотра. Офлайн-режим использует только локальную карту.",
        ),
    ),
    ("follow", ("Follow", "Слідувати", "Следовать")),
    ("fit", ("Fit route", "Увесь маршрут", "Весь маршрут")),
    ("navigation", ("Navigation", "Навігація", "Навигация")),
    (
        "position_only",
        ("Position only", "Лише позиція", "Только позиция"),
    ),
    (
        "enable",
        (
            "Enable “Share route and guidance” on Android to mirror navigation.",
            "Увімкніть «Поширювати маршрут і вказівки» на Android для відображення навігації.",
            "Включите «Передавать маршрут и указания» на Android для отображения навигации.",
        ),
    ),
    ("remaining", ("Remaining", "Залишилося", "Осталось")),
    (
        "next",
        ("Next maneuver", "Наступний маневр", "Следующий манёвр"),
    ),
    ("then", ("Then", "Потім", "Затем")),
    ("speed", ("Speed", "Швидкість", "Скорость")),
    ("kmh", ("km/h", "км/год", "км/ч")),
    ("meters", ("m", "м", "м")),
    ("minutes", ("min", "хв", "мин")),
    (
        "source",
        ("Position source", "Джерело позиції", "Источник позиции"),
    ),
    (
        "allowance",
        (
            "Navigation allowance",
            "Допуск навігації",
            "Допуск навигации",
        ),
    ),
    (
        "gps",
        (
            "GPS status / latest verdict",
            "Стан GPS / остання оцінка",
            "Состояние GPS / последняя оценка",
        ),
    ),
    (
        "jammed",
        (
            "Jamming detected",
            "Виявлено глушіння",
            "Обнаружено глушение",
        ),
    ),
    (
        "stale",
        (
            "Stale navigation — waiting for fresh data",
            "Застарілі дані — очікування оновлення",
            "Устаревшие данные — ожидание обновления",
        ),
    ),
    ("age", ("Update age", "Вік оновлення", "Возраст обновления")),
    ("arrived", ("Arrived", "Ви прибули", "Вы прибыли")),
    ("off_route", ("Off route", "Поза маршрутом", "Вне маршрута")),
    (
        "rerouting",
        ("Rerouting", "Перебудова маршруту", "Перестроение маршрута"),
    ),
    (
        "route_missing",
        (
            "Route geometry unavailable",
            "Геометрія маршруту недоступна",
            "Геометрия маршрута недоступна",
        ),
    ),
    (
        "waiting_route",
        (
            "Receiving route",
            "Отримання маршруту",
            "Получение маршрута",
        ),
    ),
    (
        "prototype",
        (
            "Research prototype, not a safety system. Plan and start navigation on Android.",
            "Дослідницький прототип, не система безпеки. Плануйте та запускайте навігацію на Android.",
            "Исследовательский прототип, не система безопасности. Планируйте и запускайте навигацию на Android.",
        ),
    ),
    (
        "private",
        (
            "Use a private hotspot, Wi-Fi or configured VPN endpoint. Sharing is unencrypted outside the VPN.",
            "Використовуйте приватну точку доступу, Wi-Fi або налаштовану VPN-адресу. Обмін поза VPN не зашифрований.",
            "Используйте частную точку доступа, Wi-Fi или настроенный VPN-адрес. Обмен вне VPN не зашифрован.",
        ),
    ),
    (
        "left",
        ("Turn left", "Поверніть ліворуч", "Поверните налево"),
    ),
    (
        "right",
        ("Turn right", "Поверніть праворуч", "Поверните направо"),
    ),
    (
        "slight_left",
        ("Bear left", "Плавно ліворуч", "Плавно налево"),
    ),
    (
        "slight_right",
        ("Bear right", "Плавно праворуч", "Плавно направо"),
    ),
    (
        "sharp_left",
        ("Sharp left", "Різко ліворуч", "Резко налево"),
    ),
    (
        "sharp_right",
        ("Sharp right", "Різко праворуч", "Резко направо"),
    ),
    ("uturn", ("Make a U-turn", "Розворот", "Разворот")),
    (
        "straight",
        (
            "Continue straight",
            "Продовжуйте прямо",
            "Продолжайте прямо",
        ),
    ),
    (
        "roundabout",
        (
            "Roundabout, exit",
            "На кільці, з’їзд",
            "На круговом движении, съезд",
        ),
    ),
    ("depart", ("Depart", "Початок руху", "Начало движения")),
    ("ramp", ("Take the ramp", "З’їзд", "Съезд")),
    ("fork", ("Keep", "Тримайтеся", "Держитесь")),
    ("merge", ("Merge", "Виїзд", "Выезд")),
    ("car", ("Car", "Авто", "Авто")),
    ("foot", ("Walking", "Пішки", "Пешком")),
    ("OK", ("Good", "Добре", "Хорошо")),
    ("GOOD", ("Good", "Добре", "Хорошо")),
    ("DEGRADED", ("Suspect", "Підозріло", "Подозрительно")),
    ("SUSPECT", ("Suspect", "Підозріло", "Подозрительно")),
    ("LOST", ("Lost", "Втрачено", "Потерян")),
    ("BAD", ("Bad", "Погано", "Плохо")),
    ("GPS", ("GPS", "GPS", "GPS")),
    (
        "GPS_SUSPECT",
        ("Suspect GPS", "Підозрілий GPS", "Подозрительный GPS"),
    ),
    ("DR", ("Dead reckoning", "Числення шляху", "Счисление пути")),
    (
        "DR_NET",
        (
            "Dead reckoning + network",
            "Числення + мережа",
            "Счисление + сеть",
        ),
    ),
    (
        "DR_OBD",
        (
            "Dead reckoning + car speed",
            "Числення + швидкість авто",
            "Счисление + скорость авто",
        ),
    ),
    ("DR_STOPPED", ("Stopped", "Зупинка", "Остановка")),
    ("CELL", ("Cell towers", "Базові станції", "Базовые станции")),
];
