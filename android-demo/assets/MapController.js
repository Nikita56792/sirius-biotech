/**
 * @typedef {{
 *    type: string,
 *    target: Map,
 *    originalEvent: MouseEvent
 * }} MMREvent
 *
 * @typedef {{x: number, y: number}} Point
 * @typedef {Point | [number, number]} PointLike
 * @typedef {'center' | 'left' | 'right' | 'top' | 'bottom' | 'top-left' | 'top-right' | 'bottom-left' | 'bottom-right'} Anchor
 * @typedef {'map' | 'viewport' | 'auto'} Alignment
 *
 * @typedef MapMouseEvent
 * @extends MMREvent<T>
 * @template T
 *
 * @typedef {{}} Popup
 * @typedef {number | PointLike | { [_: string]: PointLike }} Offset
 *
 * @typedef {{enable(): void, disable(): void}} TouchZoomRotateHandler
 * @typedef {{enable(): void, disable(): void}} DoubleClickZoomHandler
 * @typedef {{enable(): void, disable(): void}} DragPanHandler
 *
 * @typedef {{
 *     element?: HTMLElement,
 *     offset?: PointLike,
 *     anchor?: Anchor,
 *     color?: string,
 *     draggable?: boolean,
 *     clickTolerance?: number | null,
 *     rotation?: number,
 *     rotationAlignment?: Alignment,
 *     pitchAlignment?: Alignment,
 *     scale?: number,
 *     animation?: boolean,
 *     getLngLat(): LngLat
 * }} MapMarkerOptions
 *
 * @typedef {{
 *    options?: MapMarkerOptions,
 *    addTo(map: Map): this,
 *    setOffset(offset?: Offset | null): this,
 *    setLngLat(lnglat: LngLatLike): this,
 *    getPopup(): Popup
 * }} MapMarker
 *
 * @typedef {{element?: HTMLElement, options?: MapMarkerOptions}} MapMarker
 *
 * @typedef ClickableMarker
 * @extends MapMarker<T>
 * @template T
 *
 * @typedef {{
 *    enable(): void,
 *    isActive(): boolean,
 *    isEnabled(): boolean,
 *    disable(): void
 * }} TouchPitchHandler
 *
 * @typedef {{
 *    isEnabled(): boolean,
 *    enable(): void,
 *    disable(): void,
 *    isActive(): boolean,
 *    disableRotation(): void,
 *    enableRotation(): void
 * }} KeyboardHandler
 *
 * @typedef {{
 *     top: number,
 *     bottom: number,
 *     left: number,
 *     right: number,
 * }} PaddingOptions
 *
 * @typedef {{
 *    curve?: number,
 *    minZoom?: number,
 *    speed?: number,
 *    screenSpeed?: number,
 *    maxDuration?: number,
 *    animate?: boolean,
 *    padding?: PaddingOptions
 * }} FlyToOptions
 *
 * @typedef {{
 *   linear?: boolean,
 *   padding?: number | PaddingOptions,
 *   offset?: PointLike,
 *   maxZoom?: number,
 *   maxDuration?: number,
 * }} FitBoundsOptions
 *
 * @typedef {{ [key: string]: any }} EventData
 *
 * @typedef {{}} Style
 * @typedef {{}} AnyLayer
 * @typedef {{}} AnySourceData
 * @typedef {{lng: number, lat: number, distanceTo(lngLat: LngLat): number}} LngLat
 * @typedef {{sw: LngLatLike, ne: LngLatLike}} LngLatBounds
 * @typedef {LngLatBounds | [LngLatLike, LngLatLike] | [number, number, number, number] | LngLatLike} LngLatBoundsLike
 * @typedef {[number, number] | LngLat | { lng: number; lat: number } | { lon: number; lat: number } | [number, number]} LngLatLike
 *
 * @typedef {{}} MMROptions
 * @typedef {{
 *    options?: MMROptions,
 *    touchPitch: TouchPitchHandler,
 *    keyboard: KeyboardHandler,
 *    flyTo(options: FlyToOptions, eventData?: EventData): this,
 *    getZoom(): number,
 *    setMaxZoom(maxZoom?: number | null): this,
 *    setMinZoom(minZoom?: number | null): this,
 *    project(lnglat: LngLatLike): Point,
 *    unproject(point: PointLike): LngLat,
 *    _container: HTMLElement,
 *    getCanvas(): HTMLCanvasElement,
 *    getBearing(): number,
 *    getCenter(): LngLat,
 *    getBounds(): LngLatBounds,
 *    fitBounds(bounds: LngLatBoundsLike, options?: FitBoundsOptions, eventData?: EventData): this,
 *    on(type: string, listener: Function): this,
 *    setLayoutProperty(layer: string, name: string, value: any): this,
 *    removeLayer(id: string): this,
 *    removeSource(id: string): this,
 *    addLayer(layer: AnyLayer, before?: string): this,
 *    addSource(id: string, source: AnySourceData): this,
 *    touchZoomRotate: TouchZoomRotateHandler,
 *    doubleClickZoom: DoubleClickZoomHandler,
 *    dragPan: DragPanHandler,
 *    setStyle(
 *       style: Style | string,
 *       options?: { diff?: boolean; localIdeographFontFamily?: string }
 *     ): this
 * }} Map
 *
 * @typedef {{Marker: MapMarker, Map: Map }} mmrgl
 * */

/**
 * log
 * Логгер
 * @param {string} msg Сообщение
 */
function log(msg) {
  var now = new Date().toISOString().split("T").join(" ");
  var message = now + ": [JS] - " + msg;
  // console.log(message); // !!! Uncomment only in dev mode

  // on iOS using webkit callback handler
  // on Android using a custom defined LoggerHandler
  var handler =
    (window.webkit &&
      window.webkit.messageHandlers &&
      window.webkit.messageHandlers.loggerHandler) ||
    LoggerHandler;
  if (handler) {
    handler.postMessage(message);
  }
}

var markersMovementAnimationRequest = null;
var animatingMarkers = new Map();

/**
 * Animation
 * Анимация
 */
function Animation() {
  this.line = null;
  this.lineDistance = null;
  this.duration = null;
  this.startTimeStamp = null;
}

/**
 * ClickableMarker
 * Инициализирует кликабельный маркер
 */
function ClickableMarker() {
  mmrgl.Marker.apply(this, arguments);

  this.id = null;
  this.animation = null;
  this.lastMovementDate = null;
}

/**
 * ClickableMarker.prototype
 * Клонируются методы и свойства из mmrgl.Marker в ClickableMarker
 */
ClickableMarker.prototype = Object.create(mmrgl.Marker.prototype);

/**
 * ClickableMarker.constructor
 * Инициализируем конструктор
 */
ClickableMarker.prototype.constructor = ClickableMarker;

/**
 * ClickableMarker.onClick
 * Клик по маркеру
 * @param {function} handleClick Функция вызываемая в момент клика по маркеру
 */
ClickableMarker.prototype.onClick = function (handleClick) {
  this._handleClick = handleClick;
  return this;
};

/**
 * ClickableMarker._onMapClick
 * Клик по карте
 * @param {MapMouseEvent} e Объект события
 */
ClickableMarker.prototype._onMapClick = function (e) {
  var targetElement = e.originalEvent.target;
  var element = this._element;

  if (
    this._handleClick &&
    (targetElement === element || element.contains(targetElement))
  ) {
    this._handleClick();
  }
};

/**
 * ClickableMarker.move
 * Перемещение маркера
 * @param {PointLike} to Координаты названичения
 * @param {number | null} bearing Наклон
 * @param {number | null} zoom Масштаб
 * @param {boolean} animated Если true, то перемещение будет анимироваться
 * @param {number=} duration Задержка перед началом перемещения
 */
ClickableMarker.prototype.move = function (
  to,
  bearing,
  zoom,
  animated,
  duration,
) {
  if (to === undefined || to === null) {
    return;
  }

  // TODO: This is potential dangerous call. May be it should replace to `animatingMarkers.set(this.id, null)`
  animatingMarkers.delete(this.id);

  var fromCoordinate = this.getLngLat();
  var previousMovementDate = this.lastMovementDate;
  this.lastMovementDate = new Date();

  if (fromCoordinate === undefined || fromCoordinate === null || !animated) {
    this.setLngLat(to);
    this.addTo(controller.map);
    return;
  }

  var fromPoint = fromCoordinate.toArray();
  var toPoint = to;

  var animation = new Animation();

  animation.line = turf.lineString([fromPoint, toPoint]);
  animation.lineDistance = turf.distance(fromPoint, toPoint);

  if (duration === undefined || duration === null) {
    animation.duration = Math.min(
      this.lastMovementDate - previousMovementDate,
      2000,
    );
  } else {
    animation.duration = duration;
  }

  this.animation = animation;
  animatingMarkers.set(this.id, this);

  log(
    "moveMarkerAnimated. Id: " +
      this.id +
      ". Diff, time: " +
      animation.duration +
      "ms, distance: " +
      (1000 * animation.lineDistance).toFixed(2) +
      "m",
  );

  if (markersMovementAnimationRequest) {
    cancelAnimationFrame(markersMovementAnimationRequest);
  }

  markersMovementAnimationRequest = requestAnimationFrame(animateMarkers);
  if (
    this === controller.currentLocationMarker &&
    !this.isUserInteracting &&
    controller.followLocation
  ) {
    var mapBearing =
      controller.followBearing && bearing != null
        ? bearing
        : controller.map.getBearing();
    var mapZoom = zoom ? zoom : controller.map.getZoom();

    controller.map.flyTo({
      center: controller.followLocation ? to : null,
      bearing: mapBearing,
      duration: animation.duration,
      zoom: mapZoom,
      padding: mapPadding,
      animate: true,
      easing(t) {
        return t;
      },
      curve: 1.0,
      speed: 1.0,
    });
  }
};

/**
 * animateMarkers
 * Анимирование маркеров
 * @param {number} timestamp Временная метка
 */
function animateMarkers(timestamp) {
  var continueAnimation = false;

  /**
   * animateMarker
   * Анимирует маркер
   * @param {mmrgl.Marker} marker
   * @param {number} id
   */
  function animateMarker(marker, id) {
    if (marker === null || marker.animation === null) return;

    var animation = marker.animation;

    if (animation.startTimeStamp === null) {
        animation.startTimeStamp = timestamp;
    }

    var timeDelta = timestamp - animation.startTimeStamp;
    var progress = timeDelta / animation.duration;
    var progressDistance = animation.lineDistance * progress;
    var alongPoint = turf.along(animation.line, progressDistance);
    var alongCoordinates = alongPoint.geometry.coordinates;

    marker.setLngLat(alongCoordinates);
    marker.addTo(controller.map);

    if (progress < 1.0) {
      continueAnimation = true;
    } else {
      animatingMarkers.set(id, null);
    }
  }

  animatingMarkers.forEach(animateMarker);

  if (continueAnimation) {
    markersMovementAnimationRequest = requestAnimationFrame(animateMarkers);
  }
}

/**
 * Controller
 * Инициализация констроллера
 * @param {mmrgl.Map} map Эксземпляр карты
 * @param {mmrgl.Marker | ClickableMarker} marker Эксземпляр маркера
 */
function Controller(map, marker) {
  this.map = map;
  this.currentLocationMarker = marker;
  this.markers = new Map();
  this.map.touchPitch.disable();
  this.map.keyboard.disable();

  this.currentCoordinate = null;
  this.currentBearing = null;
  this.followBearing = false;
  this.followLocation = false;
  this.isUserInteracting = false;
  this.isDriveModeOn = false;
  this.isCurrentLocationHidden = false;
}

/**
 * zoomOut
 * Уменьшает масштаб карты
 * @param {number} step Шаг уменьшения
 * @param {boolean} animated Если true, уменьшение будет анимировано
 */
Controller.prototype.zoomOut = function (step, animated) {
  this.map.flyTo({
    zoom: Math.max(this.map.getZoom() - step, 1),
    animate: animated,
    padding: mapPadding,
  });
};

/**
 * zoomIn
 * Увеличивает масштаб карты
 * @param {number} step Шаг увеличения
 * @param {boolean} animated Если true, увеличение будет анимировано
 */
Controller.prototype.zoomIn = function (step, animated) {
  this.map.flyTo({
    zoom: this.map.getZoom() + step,
    animate: animated,
    padding: mapPadding,
  });
};

/**
 * setBearing
 * Устанавливает пеленг (поворот) карты
 * @param {number} bearing Градус поворота
 * @param {boolean} animated Если true, поворот будет анимирован
 */
Controller.prototype.setBearing = function (bearing, animated) {
  // Flag isUserInteracting will reset after "rotateEnd" map event
  this.isUserInteracting = true;
  this.map.flyTo({
    bearing: bearing,
    animate: animated,
    padding: mapPadding,
  });
};

/**
 * setZoom
 * Устанавливает масштаб карты
 * @param {number} zoom Масштаб
 * @param {boolean} animated Если true, изменение масштаба будет анимировано
 */
Controller.prototype.setZoom = function (zoom, animated) {
  this.map.flyTo({ zoom: zoom, animate: animated, padding: mapPadding });
};

/**
 * setMinMaxZoom
 * Устанавливает минимальный и максимальный масштаб карты
 * @param {number} minZoom Минимальный масштаб
 * @param {number} maxZoom Максимальный масштаб
 */
Controller.prototype.setMinMaxZoom = function (minZoom, maxZoom) {
  this.map.setMaxZoom(maxZoom);
  this.map.setMinZoom(minZoom);
};

/**
 * flyTo
 * Метод перемещения карты
 * @param {FlyToOptions} options Параметры для метода FlyTo
 */
Controller.prototype.flyTo = function (options) {
  // Flag isUserInteracting will reset after "moveEnd" map event
  this.isUserInteracting = true;
  if (options.padding) {
    mapPadding = options.padding;
  } else {
    options.padding = mapPadding;
  }
  this.map.flyTo(options);
};

/**
 * markerProjection
 * Координаты маркера текущего местоположения
 * относительно экрана устройства
 */
Controller.prototype.markerProjection = function () {
  return this.map.project(this.currentLocationMarker.getLngLat());
};

/**
 * mapScale
 * Определяет точный масштаб карты
 * @return number
 */
Controller.prototype.mapScale = function () {
  // A horizontal scale is imagined to be present at center of the map
  // container with maximum length (Default) as 100px.
  // Using spherical law of cosines approximation, the real distance is
  // found between the two coordinates.
  const maxWidth = 100;
  const y = this.map._container.clientHeight / 2;
  const left = this.map.unproject([0, y]);
  const right = this.map.unproject([maxWidth, y]);
  const maxMeters = left.distanceTo(right);
  return maxMeters / maxWidth;
};

/**
 * setDriveMode
 * Установка режима вождения
 * @param {boolean} on вкл/выкл
 */
Controller.prototype.setDriveMode = function (on) {
  this.isDriveModeOn = on;
  this.updateCurrentLocationMarker();
  this.setMapPadding();
};

/**
 * setFollowBearing
 * Установка режима слежки за направлением маркера текущего местоположения
 * @param {boolean} on вкл/выкл
 */
Controller.prototype.setFollowBearing = function (on) {
  this.followBearing = on;
  this.setMapPadding();
};

/**
 * setFollowLocation
 * Установка режима слежки за маркером текущего местоположения
 * @param {boolean} on вкл/выкл
 */
Controller.prototype.setFollowLocation = function (on) {
  this.followLocation = on;
  this.setMapPadding();
};

/**
 * setMapPadding
 * Установка отступов для карты
 */
Controller.prototype.setMapPadding = function () {
  if (!this.followLocation || !this.followBearing || !this.isDriveModeOn) {
    mapPadding = { top: 0, left: 0, right: 0, bottom: 0 };
  } else {
    const height = this.map.getCanvas().clientHeight;
    mapPadding = { top: height / 2, left: 0, right: 0, bottom: 0 };
  }

  this.map.flyTo({ padding: mapPadding, animate: true });
};

/**
 * sendEvent
 * Отправляет событие в postMessage (на платформу)
 * @param {string | Object} event Название события
 */
Controller.prototype.sendEvent = function (event) {
  // on iOS using webkit callback handler
  // on Android using a custom defined MapsMessageHandler
  var handler =
    (window.webkit &&
      window.webkit.messageHandlers &&
      window.webkit.messageHandlers.mapsMessageHandler) ||
    window.MapsMessageHandler;
  if (handler) {
    event.zoom = this.map.getZoom();
    event.bearing = this.map.getBearing();
    event.center = this.map.getCenter();
    event.bounds = this.map.getBounds();
    event.scale = this.mapScale();

    if (event.coords === undefined) {
      event.coords = event.center;
    }

    var project = this.map.project(event.coords);
    event.uiCoords = [project.x, project.y];

    handler.postMessage(JSON.stringify(event));
  }
};

/**
 * setCurrentLocation
 * Установка маркера текущего местоположения
 * @param {LngLatLike} coords Координаты
 * @param {number} bearing Поворот в градусах
 * @param {number} accuracy Размер окружности при плохом сигнале GPS
 * @param {number} zoom Масштаб карты
 */
Controller.prototype.setCurrentLocation = function (
  coords,
  bearing,
  accuracy,
  zoom,
) {
  if (coords) {
    this.currentLocationMarker.move(coords, bearing, zoom, true);
    currentLocation = coords;
    var el = document.querySelector('.marker_current-location')
     if (el && el.style) el.style.display = 'block'
  } else {
    currentLocation = [0, 0];
    this.currentLocationMarker.move([0, 0], 0, null, false);
  }

  currentLocationBearing = bearing;
  currentLocationAccuracy = accuracy;
  this.updateCurrentLocationBearing();
  this.updateCurrentLocationMarker();
};

/**
 * fitBounds
 * Установка границы
 * @param {any} bounds Граница
 * @param {boolean} animated Анимирует установку границы
 */
Controller.prototype.fitBounds = function (bounds, animated) {
  this.map.fitBounds(bounds, { animate: animated, padding: mapPadding });
};

/**
 * setPadding
 * Установка отступов
 * @param {{top: number, right: number, bottom: number, left: number}} padding Отступы
 * @param {boolean} animated Анимирует установку отступов
 */
Controller.prototype.setPadding = function (padding, animated) {
  mapPadding = padding;
  this.map.flyTo({ padding: padding, animate: animated });
};

/**
 * setNextPadding
 * Установка отступов для дальнейших анимаций
 * @param {{top: number, right: number, bottom: number, left: number}} padding Отступы
 */
Controller.prototype.setNextPadding = function (padding) {
  mapPadding = padding;
};

/**
 * updateCurrentLocationBearing
 * обновляет пеленг (поворот) маркера текущего местоположения
 */
Controller.prototype.updateCurrentLocationBearing = function () {
  if (!currentLocationBearing) return;
  var direction = currentLocationBearing - this.map.getBearing();
  var inner = document.querySelector("#marker_current_inner");
  inner.style.transform = "rotate(" + direction + "deg)";
};

/**
 * setCurrentLocationHidden
 * Скрыть/отобразить маркер текущего местоположения
 * @param {boolean} hidden Видимость текущего маркера
 */
Controller.prototype.setCurrentLocationHidden = function (hidden) {
  this.isCurrentLocationHidden = hidden;
  this.updateCurrentLocationMarker();
};

/**
 * updateCurrentLocationMarker
 * обновление маркера текущего местоположения
 */
Controller.prototype.updateCurrentLocationMarker = function () {
  var radius = 0;
  var bg = document.querySelector("#marker_current_bg");
  var point = document.querySelector("#marker_current_point");
  var pointWithBigAccuracy = document.querySelector(
    "#marker_current_point_with_big_accuracy",
  );
  var arrow = document.querySelector("#marker_current_arrow");
  var direction = document.querySelector("#marker_current_direction");
  var drive = document.querySelector("#marker_direction_drive");

  if(!bg || !point || !pointWithBigAccuracy || !arrow || !direction || !drive) return

  if (currentLocation[1] && currentLocationAccuracy) {
    radius = getPixelRadius(
      currentLocation[1],
      currentLocationAccuracy,
      this.map.getZoom(),
    );
  } else {
    currentLocationAccuracy = 0;
  }

  bg.style.width = radius * 2 + "px";
  bg.style.height = radius * 2 + "px";

  if (this.isCurrentLocationHidden) {
    bg.style.opacity = 0;
    point.style.opacity = 0;
    pointWithBigAccuracy.style.opacity = 0;
    arrow.style.opacity = 0;
    direction.style.opacity = 0;
    drive.style.opacity = 0;
    return;
  }

  if (this.isDriveModeOn) {
    bg.style.opacity = 0;
    point.style.opacity = 0;
    pointWithBigAccuracy.style.opacity = 0;
    arrow.style.opacity = 0;
    direction.style.opacity = 0;
    drive.style.opacity = 1;
    return;
  }

  drive.style.opacity = 0;

  if (currentLocationAccuracy >= 50) {
    bg.style.opacity = 1;
    point.style.opacity = 0;
    pointWithBigAccuracy.style.opacity = 1;
    arrow.style.opacity = 1;
    direction.style.opacity = 0;
  } else if (currentLocationAccuracy >= 15) {
    bg.style.opacity = 1;
    point.style.opacity = 1;
    pointWithBigAccuracy.style.opacity = 0;
    arrow.style.opacity = 0;
    direction.style.opacity = 1;
  } else {
    bg.style.opacity = 0;
    point.style.opacity = 1;
    pointWithBigAccuracy.style.opacity = 0;
    arrow.style.opacity = 0;
    direction.style.opacity = 1;
  }

  if (radius * 2 <= 24) {
    // center dot size
    bg.style.opacity = 0;
    point.style.opacity = 1;
    pointWithBigAccuracy.style.opacity = 0;
    arrow.style.opacity = 0;
    direction.style.opacity = 1;
  }

  if (
    currentLocationBearing === null ||
    currentLocationBearing === "undefined"
  ) {
    arrow.style.opacity = 0;
    direction.style.opacity = 0;
  }

  if (currentLocation[0] === 0 && currentLocation[1] === 0) {
    point.style.opacity = 0;
    pointWithBigAccuracy.style.opacity = 0;
  }
};

/**
 * getCoordinates
 * Получение координат на карте по координатам экрана
 * @param {PointLike} screenCoords Координаты
 * @returns LngLat
 */
Controller.prototype.getCoordinates = function (screenCoords) {
  return this.map.unproject(screenCoords);
};

/**
 * getScreenCoordinates
 * Получение координат экрана
 * @param {LngLatLike} coords Координаты
 * @returns Point
 */
Controller.prototype.getScreenCoordinates = function (coords) {
  return this.map.project(coords);
};

/**
 * removeMarker
 * Добавление маркера
 * @param {number} id ID маркера
 * @param {[number, number]} coords Координаты
 * @param {string} imageData Название изображения
 * @param {string} alignment Выравнивание относительно координат
 * @param {number} width Ширина
 * @param {number} height Высота
 * @param {number} offsetX Смещение по X
 * @param {number} offsetY Смещение по Y
 */
Controller.prototype.addMarker = function (
  id,
  coords,
  imageData,
  alignment = "center",
  width = 48,
  height = 48,
  offsetX = 0,
  offsetY = 0,
) {
  this.removeMarker(id);
  var marker = createMarker(
    id,
    coords,
    imageData,
    alignment,
    width,
    height,
    offsetX,
    offsetY,
  );
  if (marker) {
    marker.addTo(this.map);
    this.markers.set(id, marker);
  }
};

/**
 * removeMarker
 * Удаление маркера
 * @param {number} id ID маркера
 */
Controller.prototype.removeMarker = function (id) {
  var marker = this.markers.get(id);
  if (marker) {
    marker.remove();
    this.markers.delete(id);
  }
};

/**
 * removeAllMarkers
 * Изменение стиля карты
 */
Controller.prototype.removeAllMarkers = function () {
  this.markers.forEach(function (marker) {
    marker.remove();
  });
  this.markers.clear();
};

/**
 * moveMarker
 * Перемещение маркера
 * @param {number} id ID маркера
 * @param {[number, number]} coords Координаты
 * @param {boolean} animated Анимировать перемещение
 * @param {boolean} duration Задержать перед стартом
 */
Controller.prototype.moveMarker = function (id, coords, animated, duration) {
  var marker = this.markers.get(id);
  if (marker) {
    marker.move(coords, null, null, animated, duration);
  }
};

/**
 * displayPopup
 * Отображение всплывающего окна маркера (Popup)
 * @param {number} id ID маркера
 * @param {string} content HTML контент всплывающего окна
 */
Controller.prototype.displayPopup = function (id, content) {
  var marker = this.markers.get(id);
  if (marker) {
    var popupOffsets = {
      top: [0, 0],
      "top-left": [0, 0],
      "top-right": [0, 0],
      bottom: [0, -marker.height],
      "bottom-left": [0, -marker.height],
      "bottom-right": [0, -marker.height],
      left: [marker.width / 2, -marker.height / 2],
      right: [-marker.width / 2, -marker.height / 2],
    };

    marker.setPopup(
      new mmrgl.Popup({
        offset: popupOffsets,
        focusAfterOpen: false,
      }).setHTML(content),
    );
    marker.getPopup().addTo(map);
  }
};

/**
 * hidePopup
 * Скрытие всплывающего окна маркера
 * @param {number} id ID маркера
 */
Controller.prototype.hidePopup = function (id) {
  var marker = this.markers.get(id);
  if (marker) {
    marker.getPopup().remove();
  }
};

/**
 * changeStyle
 * Изменение стиля карты
 * @param {'main'|'dark'|'light'|'navigation-dark'|'navigation-main'} name Название стиля
 */
Controller.prototype.changeStyle = function (name) {
  var style = "mmr://api/styles/" + name + "_style.json";
  this.map.setStyle(style);
  log("Change style to: " + style);
};

/**
 * setupCurrentLocationMarker
 * Установка маркера текущего местоположения
 * @param {string=} hex Основной цвет маркера в HEX формате
 */
Controller.prototype.setupCurrentLocationMarker = function (hex) {
  if (!hex) hex = "#2688EB";
  var colorArrow = hex;
  var colorAccuracy = convertHex(hex, 0.2);
  var colorAccuracyBorder = "white";
  var colorCircle = convertHex(hex, 1);
  var colorCircleAccuracy = "rgb(153, 162, 173)";
  var colorCircleBorder = "white";
  var colorDirectionStart = convertHex(hex, 0.5);
  var colorDirectionMiddle = convertHex(hex, 0.05);
  var colorDirectionEnd = convertHex(hex, 0);

  if (this.currentLocationMarker) {
    this.currentLocationMarker.remove();
  }
  if (markerStyle) {
    markerStyle.remove();
  }

  var style = document.createElement("style");
  var css = `
    .marker__bg {
        background: ${colorAccuracy};
        border: 1px solid ${colorAccuracyBorder};
    }
    .marker__point {
        background: ${colorCircle};
    }
    .marker__point-accuracy:after,
    .marker__point:after {
        border: 3px solid ${colorCircleBorder};
    }
    .marker__point-accuracy {
        background: ${colorCircleAccuracy};
    }
    .marker__arrow {
        color: ${colorArrow} !important;
    }
    .marker__direction-inner {
        background: linear-gradient(180deg, ${colorDirectionStart} 0%, ${colorDirectionMiddle} 70%, ${colorDirectionEnd} 100%);
    }`;
  style.appendChild(document.createTextNode(css));
  document.getElementsByTagName("head")[0].appendChild(style);
    
  markerStyle = style;

  if (this.currentLocationMarker) {
    this.currentLocationMarker.addTo(this.map);
  }

  controller.updateCurrentLocationMarker();
};

/**
 * enableDragPan
 * Включает/выключает перемещение карты (dragPan)
 * @param {boolean} enable Если true, то карту можно перетаскивать, false - нельзя
 */
Controller.prototype.enableDragPan = function (enable) {
  if (enable) {
    this.map.dragPan.enable();
  } else {
    this.map.dragPan.disable();
  }
};

/**
 * enableZoomRotate
 * Включает/выключает поворот карты и зум при двойном клике
 * @param {boolean} enable Если true, то события работают, false - нет
 */
Controller.prototype.enableZoomRotate = function (enable) {
  if (enable) {
    this.map.touchZoomRotate.enable();
    this.map.doubleClickZoom.enable();
  } else {
    this.map.touchZoomRotate.disable();
    this.map.doubleClickZoom.disable();
  }
};

/**
 * addSource
 * Добавляет источник данных (Source)
 * @param {string} data Закодированный json в base64, является объектом типа SourceData
 * @param {string} sourceId ID источника данных
 * @param {number} lineMetrics Если указать true, то линия будет готова к закрашеванию градиентом
 */
Controller.prototype.addSource = function (data, sourceId, lineMetrics) {
  log("addSource ID: " + sourceId);

  var decodedData = window.atob(data);
  var dataJson;

  try {
    dataJson = JSON.parse(decodedData);
  } catch (e) {
    log("addSource failed to parse data: " + e);
  }

  if (!dataJson) return;

  this.map.addSource(sourceId + "-source", {
    type: "geojson",
    lineMetrics: lineMetrics,
    data: dataJson,
  });
};

/**
 * addSourcesAndLayers
 * Добавляет источники данных (Sources) и слои (Layers)
 * @param {string} sources Массив объектов типа Source закодированных в base64
 * @param {string} layers Массив объектов типа Layer закодированных в base64
 */
Controller.prototype.addSourcesAndLayers = function (sources, layers) {
  var decodedSources;
  var decodedLayers;
  try {
    decodedSources = window.atob(sources);
    decodedLayers = window.atob(layers);
  } catch (e) {
    log("addSourcesAndLayers failed to atob data: " + e);
  }

  var sourcesJSON;
  var layersJSON;
  try {
    sourcesJSON = JSON.parse(decodedSources);
    layersJSON = JSON.parse(decodedLayers);
  } catch (e) {
    log("addSourcesAndLayers failed to parse data: " + e);
  }

  if (!sourcesJSON || !layersJSON) return;
  log(
    "addSourcesAndLayers: " +
      sourcesJSON.length +
      " sources; " +
      layersJSON.length +
      " layers.",
  );

  for (var index in sourcesJSON) {
    var encodedString = sourcesJSON[index].type.encodedString;
    var geoJSON = sourcesJSON[index].type.geoJSON;
    var sourceId = sourcesJSON[index].id;
    var lineMetrics = sourcesJSON[index].lineMetrics;
    if (!sourceId) continue;

    if (encodedString) {
      var coords = decodePolylineToCoords(encodedString);
      const geoJSON = {
        type: "FeatureCollection",
        features: [
          {
            type: "Feature",
            geometry: {
              type: "LineString",
              coordinates: coords,
            },
          },
        ],
      };

      this.map.addSource(sourceId + "-source", {
        type: "geojson",
        lineMetrics: lineMetrics,
        data: geoJSON,
      });
    }

    if (geoJSON) {
      try {
        var parsed = JSON.parse(geoJSON);
        this.map.addSource(sourceId + "-source", {
          type: "geojson",
          lineMetrics: lineMetrics,
          data: parsed,
        });
      } catch (e) {
        log("failed to parse JSON: " + e);
      }
    }
  }

  for (var index in layersJSON) {
    var object = layersJSON[index];
    var layerId = object.id;
    var sourceId = object.sourceID;
    var paint = object.paint;
    var type = object.type;

    if (!layerId && !sourceId && !paint && !type) continue;

    this.map.addLayer({
      id: layerId + "-layer",
      type: type,
      source: sourceId + "-source",
      layout:
        type === "line" ? { "line-join": "round", "line-cap": "round" } : {},
      paint: paint,
    });
  }
};

/**
 * addPolylineSource
 * Добавляет источник данных с координатами для отображения линии
 * @param {string} data Закодирования линия
 * @param {string} sourceId ID источника данных
 * @param {number} lineMetrics Если указать true, то линия будет готова к закрашеванию градиентом
 */
Controller.prototype.addPolylineSource = function (
  data,
  sourceId,
  lineMetrics,
) {
  log("addPolylineSource ID: " + sourceId);

  var decodedData = window.atob(data);
  const polyline = "g|~hkB{zllrBeA~Bwg@vgAi@lA{A~CkAhCmCdGuFcNe@iAmGaNcJkSc{@ipBiHuQuFeMyB}E{EuKoK}Um\\mv@sdAe_CsbAi|BsQea@oHkPyAeDyCkGeDeH}FsLyKaW{S_e@cp@kzAckAwjCy\\iw@uK{Vg]wx@{DgJeEyJki@aiAkOm]{[yt@{k@isAcQe[ca@uk@s|@ciAoCoDs[}c@_y@yfAoDsJiHiMsCmJqBoHeBmJ_@mC_@wBiAiKi@kHYmHm@{Y[_ZVa`@pAs\\lBcYfDeVxEaZbn@usCbXqnAr]k`Bxa@elBtZimAxZ{jAx[kmAnfCseJblAqrE|tAibFzlAakE`Oci@`AkD`Sos@zGyVxq@weCxAuFlB{GbDsLdJq^ztA_gFpc@ikBlS_|@|Lkz@hKopA`Der@p@os@G}z@}Aur@iGyrAuFelAc\\s}Fy^goFgFks@oH}eAmDq`@uWwwCmMqhBuCsj@qAgi@y@cg@Yoi@Jma@b@im@|Ayp@|Cew@pC}k@xEmt@xDqe@rVocBrGm]~SgcAtD{Qj]m_BpKun@bRikAjEkYdGod@jLyy@`E_d@tEsn@bEe|@pKczBzFalAzG_yAp@kc@hAcgAFcCN_z@WmHmEgv@wFqq@iIyp@mHsd@{Nos@mKud@iQmg@qRef@kQs^mSk[i\\wa@a`@ga@{u@c{@{NaNgR}LmPoGyf@cRg~Bor@s~Akg@gYwK_q@oS}v@eTo^cMyZiQoXkSePgQ{McQo`@{i@mLeSgGmLmG}O}No^eMu[oPeb@mS_i@eXwr@yEaMuBsGsNg^eH_Paa@c~@}\\sk@cMeRwTmZmS_VySmTkUgTkUiQiLkHiRaL_[sOce@qQad@uQedAeb@e}Aml@uBy@gBq@mYqKqa@}OuNqEoNaDoSgC}TsAqMWsd@nAmKd@cHn@}VbFqtAnV}c@`IqDn@eUlDiNbCif@vFqp@`K}rAoBiUyCmZ}EqP{F_UmNeMoKiNiOqOqRy]sd@stAohBsgDspEyWkZaWoWyVa[BOmAzE}FyIyImLcOuPmIeDgGsAoBSgAO_AMuMTaGn@{GhAiJtB}NhDym@~Ny^bJiKlC}Cv@qGxAkGpBynAr\\y\\pIcf@|L_TvE{[~IoMdD_Dr@{GfB{Cv@{MjDeDj@iLhD[Jmi@dOuTpGkPxEeKtCwMpFwQ~E}Ab@}Bl@gEjAoWbHyJpCqMpDwBj@iPhEgAXmi@xNoRnFyFxAoFvA_Cl@u@TqAb@sDdA_e@bMsFzA{EnAq@RiWdHgCn@}k@fP{e@nMa\\lJoBp@qA\\ec@pLkUpGss@tQuA\\eDv@iAZk@N{TrFqBd@cGzAeCj@qSpFyA^_r@xQmCt@kUfGmg@jNwj@hNgr@hRyStF_PhEoBh@qBb@mInBkm@lN_VvFm@uKuF}dAyAiXwDas@}GcpAqFucA[_GYgFkHcrAcE}u@wBl@od@nMaBeZiS`GyEa}@"
  var coords = decodePolylineToCoords(decodedData);
  log("Coords length " + coords.length);
  log("Coords base64 " + data);
  log("Coords polyline: " + polyline);
  log("Coords decoded string: " + decodedData);

  const geoJSON = {
    type: "FeatureCollection",
    features: [
      {
        type: "Feature",
        geometry: {
          type: "LineString",
          coordinates: coords,
        },
      },
    ],
  };

  this.map.addSource(sourceId + "-source", {
    type: "geojson",
    lineMetrics: lineMetrics,
    data: geoJSON,
  });
  var parsed = JSON.stringify(geoJSON);
  log("Coords result geoJson: " + parsed);
};

/**
 * addCircleSource
 * Добавляет источник данных с координатами для отображения круга
 * @param {LngLatLike} coords Координаты
 * @param {string} sourceId ID источника данных (Source)
 * @param {number} radius Радиус круга
 * @param {any} steps Шаг с которым будут создаваться кординаты для отрисовки круга
 * @returns string GeoJSON
 */
Controller.prototype.addCircleSource = function (
  coords,
  radius,
  steps,
  sourceId,
) {
  var options = { steps: steps, units: "kilometers", properties: {} };
  var circle = turf.circle(coords, radius / 1000, options);

  this.map.addSource(sourceId + "-source", {
    type: "geojson",
    data: circle,
  });
  return JSON.stringify(circle);
};

var clusterIDs = [];
const clusters = {};

/**
 * addCluster
 * Удаляет кластер
 * @param {string} markersData JSON с данными о маркерах закодированый в base64
 * @param {string} sourceId ID источника данных (Source)
 * @param {number} radius Радиус кластеризации
 * @param {string} background Цвет фона
 * @param {string} color Цвет текста
 */
Controller.prototype.addCluster = function (
  markersData,
  sourceId,
  radius,
  background,
  color,
) {
  var decodedData = window.atob(markersData);
  var markersJson;

  try {
    markersJson = JSON.parse(decodedData);
  } catch (e) {
    log("addClusters failed to parse data: " + e);
  }

  if (!markersJson) return;

  var features = [];
  for (const marker of markersJson) {
    features.push({
      type: "Feature",
      properties: {
        id: marker.id,
        image: marker.image,
        height: marker.height,
        width: marker.width,
        alignment: marker.alignment,
        offsetX: marker.offsetX,
        offsetY: marker.offsetY,
      },
      geometry: {
        type: "Point",
        coordinates: marker.coords,
      },
    });
  }

  const dataJson = {
    type: "FeatureCollection",
    features: features,
  };

  const id = sourceId + "-clusterSource";
  this.map.addSource(id, {
    type: "geojson",
    data: dataJson,
    cluster: true,
    clusterRadius: radius || 80,
  });

  // add placeholder layer to get source features
  map.addLayer({
    id: sourceId + "-clusterLayer",
    type: "line",
    source: id,
  });

  clusterIDs.push(id);
  clusters[id] = {
    markers: {},
    markersOnScreen: {},
    background: background || "#1f6ff9",
    color: color || "white",
  };

  controller.updateClusterMarkers();
};

/**
 * removeCluster
 * Удаляет кластер
 * @param {string} id ID кластера
 */
Controller.prototype.removeCluster = function (id) {
  try {
    var sourceId = id + "-clusterSource";
    for (const markerId in clusters[sourceId].markersOnScreen) {
      clusters[sourceId].markersOnScreen[markerId].remove();
      this.markers.delete(markerId);
    }

    clusterIDs.splice(clusterIDs.indexOf(sourceId), 1);
    delete clusters[sourceId];
    this.map.removeLayer(id + "-clusterLayer");
    this.map.removeSource(sourceId);
  } catch (e) {
    log("removeCluster failed, id: " + id + ", e: " + e);
  }
};

/**
 * updateClusterMarkers
 * Обновляет все кластеры
 */
Controller.prototype.updateClusterMarkers = function () {
  for (const id of clusterIDs) {
    controller.updateClusterMarker(id);
  }
};

/**
 * updateClusterMarker
 * Обновляет кластер и его маркеры
 * @param {string} sourceId ID источника данных (Source)
 */
Controller.prototype.updateClusterMarker = function (sourceId) {
  const newMarkers = {};
  const features = map.querySourceFeatures(sourceId);

  for (const feature of features) {
    const coords = feature.geometry.coordinates;
    const props = feature.properties;
    const id = props.cluster ? props.cluster_id : props.id;
    const alignment = props.alignment || "center";

    var marker = clusters[sourceId].markers[id];

    if (!marker) {
      if (props.cluster) {
        const el = document.createElement("div");
        el.className = "cluster";
        el.innerText = props.point_count;
        el.style.background = clusters[sourceId].background;
        el.style.color = clusters[sourceId].color;

        el.addEventListener("click", function (e) {
          e.stopPropagation();
          log("clickOnCluster, coords: " + coords);
          controller.sendEvent({
            type: "clickOnCluster",
            coords: { lng: coords[0], lat: coords[1] },
            userInitiated: true,
          });
        });

        marker = clusters[sourceId].markers[id] = new mmrgl.Marker({
          element: el,
        }).setLngLat(coords);
      } else {
        marker = clusters[sourceId].markers[id] = createMarker(
          id,
          coords,
          props.image,
          alignment,
          props.width,
          props.height,
          props.offsetX,
          props.offsetY,
        );
      }
    }

    newMarkers[id] = marker;

    if (!clusters[sourceId].markersOnScreen[id]) {
      marker.addTo(map);
      this.markers.set(id, marker);
    }
  }

  // Удаляем маркеры которые не видны больше
  for (const id in clusters[sourceId].markersOnScreen) {
    if (!newMarkers[id]) {
      clusters[sourceId].markersOnScreen[id].remove();
      this.markers.delete(id);
    }
  }
  clusters[sourceId].markersOnScreen = newMarkers;
};

/**
 * addLayer
 * Добавляет слой отображения (Layer) для источника данных (Source)
 * @param {string} layerId ID слоя (Layer)
 * @param {string} sourceId ID источника данных (Source)
 * @param {string} type Тип слоя
 * @param {string} paint Свойства группы Paint
 */
Controller.prototype.addLayer = function (layerId, sourceId, type, paint) {
  var paintJson;

  try {
    paintJson = JSON.parse(paint);
  } catch (e) {
    log("addLayer ID: " + layerId + " for sourceId: " + sourceId + "e: " + e);
  }

  if (!paintJson) return;

  this.map.addLayer({
    id: layerId + "-layer",
    type: type,
    source: sourceId + "-source",
    layout:
      type === "line" ? { "line-join": "round", "line-cap": "round" } : {},
    paint: paintJson,
  });
};

/**
 * removeSource
 * Удаляет источник данных (Source)
 * @param {string} sourceId ID источника данных (Source)
 */
Controller.prototype.removeSource = function (sourceId) {
  try {
    this.map.removeSource(sourceId + "-source");
  } catch (e) {
    log("removeSource failed, sourceId: " + sourceId + ", e: " + e);
  }
};

/**
 * removeLayer
 * Удаляет слой (Layer)
 * @param {string} layerId ID слоя (Layer)
 */
Controller.prototype.removeLayer = function (layerId) {
  try {
    this.map.removeLayer(layerId + "-layer");
  } catch (e) {
    log("removeLayer failed, layerId: " + layerId + ", e: " + e);
  }
};

/**
 * setLayoutVisible
 * Изменяет свойство видимости слоя (Layer)
 * @param {string} layerId ID слоя (Layer)
 * @param {boolean} visible Если установить false, то слой будет скрыт
 */
Controller.prototype.setLayoutVisible = function (layerId, visible) {
  try {
    this.map.setLayoutProperty(
      layerId,
      "visibility",
      visible ? "visible" : "none",
    );
  } catch (e) {
    log("Layer: " + layerId + " set " + visible + ", e: " + e);
  }
};

/**
 * longTap
 * Вызывает событие longPress в момент долгого нажатия
 * @param {PointLike} screenCoords Координаты экрана
 */
Controller.prototype.longTap = function (screenCoords) {
  var project = this.map.unproject(screenCoords);
  var isUserInitiated = true;
  controller.sendEvent({
    type: "longPress",
    coords: project,
    userInitiated: true,
  });
};

/**
 * createCurrentLocationMarker
 * Создание маркера текущего местоположения пользователя
 * @returns ClickableMarker
 */
function createCurrentLocationMarker() {
  var el = document.querySelector('.marker_current-location')
  var marker = new ClickableMarker(el);
  marker.setOffset([0, 24]);
  return marker;
}

/**
 * createMarker
 * Создание маркера
 * @param {number} id ID маркера
 * @param {[number, number]} coords Координаты
 * @param {string} imageData Название изображения
 * @param {string} alignment Выравнивание относительно координат
 * @param {number} width Ширина
 * @param {number} height Высота
 * @param {number} offsetX Смещение по X
 * @param {number} offsetY Смещение по Y
 */
function createMarker(
  id,
  coords,
  imageData,
  alignment = "center",
  width = 48,
  height = 48,
  offsetX = 0,
  offsetY = 0,
) {
  log(
    "Will created marker: width " +
      width +
      "pt, height " +
      height +
      "px, offsetX " +
      offsetX +
      "px, offsetY " +
      offsetY,
  );
  var el = document.createElement("div");
  el.id = "marker_" + id;
  el.className = "marker";

  if (imageData) {
    el.setAttribute(
      "style",
      "background-image: url('mapssdk://?markerID=" +
        imageData +
        "');" +
        " width: " +
        width +
        "px;" +
        " height: " +
        height +
        "px;",
    );
  }

  var marker = new ClickableMarker(el);
  marker.id = id;
  marker.width = width;
  marker.height = height;
  marker.setLngLat(coords);

  if (alignment === "center") {
    marker.setOffset([offsetX, offsetY]);
  } else if (alignment === "bottom") {
    marker.setOffset([offsetX, -height / 2 + offsetY]);
  } else if (alignment === "top") {
    marker.setOffset([offsetX, height / 2 + offsetY]);
  } else if (alignment === "right") {
    marker.setOffset([width / 2 + offsetX, offsetY]);
  } else if (alignment === "left") {
    marker.setOffset([-width / 2 + offsetX, offsetY]);
  } else if (alignment === "bottomLeft") {
    marker.setOffset([width / 2 + offsetX, -height / 2 + offsetY]);
  } else if (alignment === "bottomRight") {
    marker.setOffset([-width / 2 + offsetX, -height / 2 + offsetY]);
  } else if (alignment === "topLeft") {
    marker.setOffset([width / 2 + offsetX, height / 2 + offsetY]);
  } else if (alignment === "topRight") {
    marker.setOffset([-width / 2 + offsetX, height / 2 + offsetY]);
  }

  el.addEventListener("click", function (e) {
    e.stopPropagation();
    controller.sendEvent({
      type: "clickOnMarker",
      markerId: id,
      userInitiated: true,
    });
  });

  return marker;
}

/**
 * metersPerPixel
 * Преобразует метры в пиксели относительно широты с масштаба карты
 * @param {number} latitude Широта
 * @param {number} zoomLevel Масштаб
 * @returns number
 */
function metersPerPixel(latitude, zoomLevel) {
  var earthCircumference = 40075017;
  var latitudeRadians = latitude * (Math.PI / 180);
  return (
    (earthCircumference * Math.cos(latitudeRadians)) /
    Math.pow(2, zoomLevel + 9)
  );
}

/**
 * getPixelRadius
 * Возвращает радиус окружности в пикселях
 * @param {number} latitude Широта
 * @param {number} meters Радиус в метрах
 * @param {number} zoomLevel Масштаб
 * @returns number
 */
function getPixelRadius(latitude, meters, zoomLevel) {
  return meters / metersPerPixel(latitude, zoomLevel);
}

/**
 * convertHex
 * Преобразует HEX цвет в RGBA
 * @param {string} hexCode Цвет в формате HEX
 * @param {number} opacity Прозрачность будущего RGBA
 * */
function convertHex(hexCode, opacity = 1) {
  var hex = hexCode.replace("#", "");

  if (hex.length === 3) {
    hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
  }

  var r = parseInt(hex.substring(0, 2), 16),
    g = parseInt(hex.substring(2, 4), 16),
    b = parseInt(hex.substring(4, 6), 16);

  /* Backward compatibility for whole number based opacity values. */
  if (opacity > 1 && opacity <= 100) {
    opacity = opacity / 100;
  }

  return "rgba(" + r + "," + g + "," + b + "," + opacity + ")";
}

/**
 * decodePolylineToCoords
 * Декодирует строку в массив точек
 * @param {string} encodedString Кодированная линия
 * @returns {[number, number][]} Массив координат вида [lng, lat]
 * @type {(encodedString: string) => [number, number][]}
 */
function decodePolylineToCoords(encodedString) {
  var coordinates = [],
    factor = 1000000;

  var index = 0;
  var lat = 0;
  var lng = 0;
  var shift = 0;
  var result = 0;
  var byte = null;
  var latitude_change;
  var longitude_change;

  // Coordinates have variable length when encoded, so just keep
  // track of whether we've hit the end of the string. In each
  // loop iteration, a single coordinate is decoded.
  while (index < encodedString.length) {
    // Reset shift, result, and byte
    byte = null;
    shift = 0;
    result = 0;

    do {
      byte = encodedString.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);

    latitude_change = result & 1 ? ~(result >> 1) : result >> 1;

    shift = result = 0;

    do {
      byte = encodedString.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);

    longitude_change = result & 1 ? ~(result >> 1) : result >> 1;

    lat += latitude_change;
    lng += longitude_change;

    coordinates.push([lng / factor, lat / factor]);
  }

  return coordinates;
}

/**
 * load
 * Всегда возвращает "ok"
 * @returns string "ok"
 */
function load() {
  return "ok";
}

/**
 * Событие click срабатывает момент клика по карте
 * @param {MapMouseEvent} e Объект события
 */
map.on("click", function (e) {
  var isUserInitiated = typeof e.originalEvent !== "undefined";
  controller.sendEvent({
    type: "touch",
    coords: e.lngLat,
    userInitiated: typeof e.originalEvent !== "undefined",
  });
});

/**
 * Событие zoomstart срабатывает при старте события zoom,
 * @param {MapMouseEvent} e Объект события
 */
map.on("zoomstart", function (e) {
  var isUserInitiated = typeof e.originalEvent !== "undefined";
  controller.isUserInteracting = isUserInitiated;
  controller.sendEvent({ type: "zoomStart", userInitiated: isUserInitiated });
  controller.updateCurrentLocationMarker();
});

/**
 * Событие zoom срабатывает в момент изменения масштаба карты
 * @param {MapMouseEvent} e Объект события
 */
map.on("zoom", function (e) {
  var isUserInitiated = typeof e.originalEvent !== "undefined";
  controller.sendEvent({ type: "zoom", userInitiated: isUserInitiated });
  controller.updateCurrentLocationMarker();
});

/**
 * Событие zoomend срабатывает в конце события zoom,
 * @param {MapMouseEvent} e Объект события
 */
map.on("zoomend", function (e) {
  var isUserInitiated = typeof e.originalEvent !== "undefined";
  controller.sendEvent({ type: "zoomEnd", userInitiated: isUserInitiated });
  controller.isUserInteracting = false;
  controller.updateCurrentLocationMarker();
});

/**
 * Событие rotatestart срабатывает при старте события rotate
 * @param {MapMouseEvent} e Объект события
 */
map.on("rotatestart", function (e) {
  var isUserInitiated = typeof e.originalEvent !== "undefined";
  controller.isUserInteracting = isUserInitiated;
  controller.sendEvent({
    type: "rotateStart",
    userInitiated: isUserInitiated,
  });
  controller.updateCurrentLocationBearing();
});

/**
 * Событие rotate срабатывает при повороте карты
 * @param {MapMouseEvent} e Объект события
 */
map.on("rotate", function (e) {
  var isUserInitiated = typeof e.originalEvent !== "undefined";
  controller.sendEvent({ type: "rotate", userInitiated: isUserInitiated });
  controller.updateCurrentLocationBearing();
});

/**
 * Событие rotateEnd срабатывает в момент завершения события rotate
 * @param {MapMouseEvent} e Объект события
 */
map.on("rotateend", function (e) {
  var isUserInitiated = typeof e.originalEvent !== "undefined";
  controller.sendEvent({ type: "rotateEnd", userInitiated: isUserInitiated });
  controller.isUserInteracting = false;
  controller.updateCurrentLocationBearing();
});

/**
 * Событие movestart срабатывает в момент старта события move
 * @param {MapMouseEvent} e Объект события
 */
map.on("movestart", function (e) {
  var isUserInitiated = typeof e.originalEvent !== "undefined";
  controller.isUserInteracting = isUserInitiated;
  controller.sendEvent({ type: "moveStart", userInitiated: isUserInitiated });
});

/**
 * Событие move срабатывает в момент перемещения карты
 * @param {MapMouseEvent} e Объект события
 */
map.on("move", function (e) {
  var isUserInitiated = typeof e.originalEvent !== "undefined";
  controller.sendEvent({ type: "move", userInitiated: isUserInitiated });
});

/**
 * Событие moveend срабатывает в конце события move
 * @param {MapMouseEvent} e Объект события
 */
map.on("moveend", function (e) {
  var isUserInitiated = typeof e.originalEvent !== "undefined";
  controller.sendEvent({ type: "moveEnd", userInitiated: isUserInitiated });
  controller.isUserInteracting = false;
});

/** Событие load срабатывает в момент полной загрузки карты */
map.on("load", function () {
  controller.sendEvent({ type: "load", userInitiated: false });
});

/** Событие style.load срабатывает в момент загрузки стиле */
map.on("style.load", function () {
  controller.sendEvent({ type: "styleLoad", userInitiated: false });
});

/** Событие render срабатывает каждый раз когда карта перерисовывается */
map.on("render", function () {
  controller.updateClusterMarkers();
});

/**
 * Инициализация переменных
 */
var markerStyle;
var currentLocationMarkerId = String(Date.now() + Math.random());
var currentLocationMarker = createCurrentLocationMarker();
currentLocationMarker.id = currentLocationMarkerId;
var currentLocationBearing = 0;
var currentLocation = [0, 0];
var currentLocationAccuracy = 0;
var mapPadding = { top: 0, left: 0, right: 0, bottom: 0 };


/**
 * Send "clickOnMarker" event to controller.
 * Right now, controller doesn't support click on current location marker.
 */
currentLocationMarker.onClick(function () {});

var controller = new Controller(map, currentLocationMarker);

load();
