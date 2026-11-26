/* @plugin
{
  "id": "builtin.effects",
  "name": "Эффекты",
  "version": "1.1",
  "author": "Expressive Keyboard",
  "description": "Пример мода на API 4: искры и жидкость отскакивают от клавиш и реагируют на палец, шейдер, наклон клавиатуры. Настройки разбиты на вкладки, есть пресеты.",
  "icon": "@magic",
  "enabledByDefault": false,
  "permissions": ["sensors"],
  "config": [
    { "type": "tab", "title": "Частицы" },
    { "id": "sparks", "title": "Искры под пальцем", "type": "toggle", "default": false },
    { "id": "spark_color", "title": "Цвет искр", "type": "color", "default": "#FFB74D", "showIf": "sparks" },
    { "id": "liquid", "title": "Жидкость", "type": "toggle", "default": false, "description": "Течёт вслед за наклоном телефона и обтекает клавиши" },
    { "type": "section", "title": "Настройка жидкости", "collapsed": true },
    { "id": "liquid_count", "title": "Количество", "type": "slider", "min": 200, "max": 3000, "step": 100, "default": 1000, "showIf": "liquid" },
    { "id": "liquid_color", "title": "Цвет", "type": "color", "default": "#664FC3F7", "showIf": "liquid" },
    { "type": "section", "title": "Настройка искр", "collapsed": true },
    { "id": "spark_color2", "title": "Второй цвет искр", "type": "color", "default": "#FFFFFF", "showIf": "sparks" },
    { "id": "spark_amount", "title": "Количество при касании", "type": "slider", "min": 1, "max": 30, "step": 1, "default": 8, "showIf": "sparks" },
    { "id": "spark_size", "title": "Размер искры", "type": "slider", "min": 1, "max": 10, "step": 1, "default": 3, "showIf": "sparks" },
    { "id": "spark_gravity", "title": "Падение (минус — вверх)", "type": "slider", "min": -300, "max": 1500, "step": 50, "default": 500, "showIf": "sparks" },
    { "id": "spark_life", "title": "Время жизни искры, мс", "type": "slider", "min": 200, "max": 3000, "step": 100, "default": 900, "showIf": "sparks" },
    { "id": "spark_speed", "title": "Скорость вылета", "type": "slider", "min": 20, "max": 600, "step": 10, "default": 220, "showIf": "sparks" },
    { "id": "spark_keys", "title": "Отскакивают от клавиш", "type": "toggle", "default": true, "showIf": "sparks" },

    { "type": "tab", "title": "Шейдер" },
    { "id": "wave", "title": "Волна по клавиатуре", "type": "toggle", "default": false, "description": "Android 13 и новее" },
    { "id": "wave_power", "title": "Сила волны", "type": "slider", "min": 1, "max": 16, "step": 1, "default": 4, "showIf": "wave" },

    { "type": "tab", "title": "Наклон" },
    { "id": "parallax", "title": "Наклон за телефоном", "type": "toggle", "default": false },
    { "id": "parallax_power", "title": "Сила наклона", "type": "slider", "min": 2, "max": 30, "step": 1, "default": 10, "showIf": "parallax" },

    { "type": "tab", "title": "Пресеты" },
    { "id": "preset_calm", "type": "preset", "title": "Спокойный", "values": { "sparks": false, "liquid": false, "wave": false, "parallax": false } },
    { "id": "preset_sparks", "type": "preset", "title": "Искры", "values": { "sparks": true, "spark_color": "#FFB74D", "spark_color2": "#FFFFFF", "spark_size": 3, "spark_amount": 8, "spark_gravity": 500, "spark_life": 900, "spark_speed": 220, "spark_keys": true } },
    { "id": "preset_sand", "type": "preset", "title": "Песок", "values": { "sparks": true, "spark_color": "#D9B779", "spark_color2": "#B8935A", "spark_size": 2, "spark_amount": 14, "spark_gravity": 900, "spark_life": 1400, "spark_speed": 150, "spark_keys": true } },
    { "id": "preset_snow", "type": "preset", "title": "Снег", "values": { "sparks": true, "spark_color": "#FFFFFF", "spark_color2": "#CFE8FF", "spark_size": 4, "spark_amount": 4, "spark_gravity": 120, "spark_life": 2500, "spark_speed": 60, "spark_keys": false } },
    { "id": "preset_dev", "type": "preset", "title": "Всё сразу", "values": { "sparks": true, "liquid": true, "wave": true, "parallax": true, "wave_power": 6 } },
    { "id": "import", "title": "Свой пресет (JSON)", "type": "textarea", "hint": "{ \"sparks\": true, \"liquid\": false }",
      "description": "Вставьте текст и нажмите галочку" }
  ]
}
*/

var WAVE = [
  "uniform shader content; uniform float iTime; uniform float power;",
  "half4 main(float2 p) {",
  "  p.x += sin(p.y * 0.035 + iTime * 2.5) * power;",
  "  return content.eval(p);",
  "}"
].join("\n");

function apply() {
  var c = kb.config();
  var size = kb.keyboardSize();

  if (c.sparks) {
    kb.particles.set("sparks", {
      max: 4000, size: c.spark_size, colors: [c.spark_color, c.spark_color2], gravity: c.spark_gravity, drag: 0.6,
      life: c.spark_life / 1000, bounds: "kill", collideKeys: c.spark_keys, emitOnTouch: c.spark_amount,
      speed: c.spark_speed, layer: "overlay"
    });
  } else kb.particles.remove("sparks");

  var wasLiquid = kb.particles.count("liquid") > 0;
  if (c.liquid) {
    kb.particles.set("liquid", {
      max: c.liquid_count, liquid: true, size: 7, color: c.liquid_color, gravity: 350, tilt: 900,
      bounce: 0.2, drag: 0.4, collideKeys: true, touchForce: 6000, touchRadius: 70
    });
    if (!wasLiquid && size.width > 0) kb.particles.emit("liquid", { count: c.liquid_count, x: 0, y: 0, w: size.width, h: size.height / 3 });
  } else kb.particles.remove("liquid");

  if (c.wave && kb.supports("postEffect")) kb.postEffect({ src: WAVE, uniforms: { power: c.wave_power } });
  else kb.postEffect(null);

  if (!c.parallax) kb.keyboardEffect(null);
}

kb.on("tilt", function (t) {
  if (!kb.config("parallax")) return;
  var k = kb.config("parallax_power");
  kb.keyboardEffect({ rotationY: t.x * k, rotationX: -t.y * k, camera: 20 });
});

kb.on("option", function (e) {
  if (e.id === "import") {
    if (!e.value) return;
    try {
      kb.setConfigs(JSON.parse(e.value));
    } catch (err) {
      kb.toast("Пресет: это не JSON");
    }
    return;
  }
  apply();
});

// при загрузке клавиатура может быть ещё без размеров: жидкость заполнит её при показе
kb.on("show", apply);
apply();
