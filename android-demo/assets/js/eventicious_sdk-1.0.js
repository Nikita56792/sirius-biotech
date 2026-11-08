(function () {

    String.prototype.hashCode = function() {
        var hash = 0, i, chr;
        if (this.length === 0) return hash;
        for (i = 0; i < this.length; i++) {
            chr = this.charCodeAt(i);
            hash = ((hash << 5) - hash) + chr;
            hash |= 0;
        }
        return hash;
    };

    var callbacks = {};

    window._ev_internal = new Object();
    window._ev_internal.evaluateCallback = function () {
        const id = arguments[0];
        const args = parseCallbackArgs(arguments);
        callbacks[id].apply(null, args);
    }
    window._ev_internal.evaluateOneTimeCallback = function () {
        const id = arguments[0];
        const args = parseCallbackArgs(arguments);
        callbacks[id].apply(null, args);
        delete callbacks[id];
    }

    function parseCallbackArgs(args) {
        var callbackArgs = new Array(args.length - 1);
        for (var i = 0; i < args.length - 1; ++i) {
            var argument = args[i + 1];
            if ((typeof argument === 'string' || argument instanceof String) && argument.indexOf('[json]') === 0) {
                argument = JSON.parse(argument.replace('[json]', ''));
            }
            callbackArgs[i] = argument;
        }
        return callbackArgs;
    }

    function generateUuid() {
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
            var r = Math.random()*16|0, v = c == 'x' ? r : (r&0x3|0x8);
            return v.toString(16);
        });
    }

    function saveCallback(callback) {
        var id = generateUuid();
        callbacks[id] = callback;
        return id;
    }

    var EventiciousSDK = {}

    // Analytics
    EventiciousSDK.analytics = {}

    EventiciousSDK.analytics.trackScreen = (screenName) => {
        if (screenName) {
            JsAnalytics.trackScreen(screenName)
        }
    }

    EventiciousSDK.analytics.trackEvent = (category, action, label) => {
        if (category && action) {
            JsAnalytics.trackEvent(category, action, label)
        }
    }

    // Profiles Manager
    EventiciousSDK.profilesManager = {}

    EventiciousSDK.profilesManager.authorize = (eventId, onSuccess, onError) => {
        JsAuthenticator.authorize(eventId, saveCallback(onSuccess), saveCallback(onError));
    }

    EventiciousSDK.profilesManager.getProfile = (eventId) => {
        var profile = JsAuthenticator.getProfile(eventId);
        if (profile) {
            return JSON.parse(profile);
        } else {
            return null;
        }
    }

    EventiciousSDK.profilesManager.updateProfile = (eventId, onSuccess, onError) => {
        JsContentManager.updateProfile(eventId, saveCallback(onSuccess), saveCallback(onError));
    }

    // Image Picker
    EventiciousSDK.imagePicker = {}

    EventiciousSDK.imagePicker.sendImage = (url, onSuccess, onCancel, onError) =>
        JsImagePicker.sendImage(url, saveCallback(onSuccess), saveCallback(onCancel), saveCallback(onError))

    // Alerts
    EventiciousSDK.customAlert = (title, message, callback) => {
        callback = callback || function(){};
        JsAlerts.showAlert(title, message, saveCallback(callback));
    }

    // Data
    EventiciousSDK.appSettings = () =>
        JSON.parse(JsDataProvider.getAppSettings())

    EventiciousSDK.confSettings = () =>
        JSON.parse(JsDataProvider.getEventSettings())

    EventiciousSDK.locale = () =>
        JsDataProvider.getLocale()

    EventiciousSDK.getCurrentConferenceId = () =>
        JsDataProvider.getCurrentEventId()

    EventiciousSDK.getUserGUID = () =>
        JsDataProvider.getInstallationId()

    EventiciousSDK.beginAttendeesUpdate = (eventId) =>
        JsContentManager.updateAttendees(eventId)

    // Scorm
    EventiciousSDK.scorm = {}

    EventiciousSDK.scorm.getState = (scormId, trackId) =>
        new Promise((resolve, reject) => {
            const onSuccess = (state) => resolve(state);
            const onError = (reason) => reject(reason);
            JsScorm.getState(scormId, trackId, saveCallback(onSuccess), saveCallback(onError));
        })

    EventiciousSDK.scorm.setState = (scormId, state, trackId) =>
        new Promise((resolve, reject) => {
            const onSuccess = (state) => resolve(state);
            const onError = (reason) => reject(reason);
            JsScorm.setState(scormId, state, trackId, saveCallback(onSuccess), saveCallback(onError));
        })

    EventiciousSDK.scorm.showNextButton = () =>
        JsScorm.showNextButton()

    // Environment
    EventiciousSDK.getEnv = () =>
        JsEnvironment.getEnvironment()

    EventiciousSDK.getNetworkConnectionType = () =>
        JsEnvironment.getNetworkConnectionType()

    EventiciousSDK.screenShouldAutorotate = (autorotate) =>
        JsEnvironment.setScreenRotationEnabled(autorotate)

    // Navigation
    EventiciousSDK.showMenu = () =>
        JsNavigator.openMenu()

    EventiciousSDK.close = () =>
        JsNavigator.close()

    EventiciousSDK.openAppPath = (url, properties) =>
        JsNavigator.open(url, JSON.stringify(properties))


    // Toolbar
    EventiciousSDK.setTitle = (title) =>
        JsToolbar.setTitle(title)

    EventiciousSDK.resetTitle = () =>
        JsToolbar.resetTitle()

    EventiciousSDK.configureOptionsMenu = (options) => {
        const actions = options.map(value => {
            return {
              name: value.name,
              action: saveCallback(value.action)
            }
        });
        const configuration = JSON.stringify(actions);
        JsToolbar.configureOptionsMenu(configuration);
    }

    EventiciousSDK.removeOptionsMenu = () =>
        JsToolbar.removeOptionsMenu()

    EventiciousSDK.Buttons = {
        Left: {},
        Right: {}
    }

    EventiciousSDK.Buttons.Left.setEnabled = (enabled) =>
        JsToolbar.setStartButtonEnabled(enabled)

    EventiciousSDK.Buttons.Right.setEnabled = (enabled) =>
        JsToolbar.setEndButtonEnabled(enabled)

    EventiciousSDK.Buttons.Left.setVisible = (visible) =>
        JsToolbar.setStartButtonVisible(visible)

    EventiciousSDK.Buttons.Right.setVisible = (visible) =>
        JsToolbar.setEndButtonVisible(visible)

    EventiciousSDK.Buttons.Left.setImageType = (type) =>
        JsToolbar.setStartButtonImage(type)

    EventiciousSDK.Buttons.Right.setImageType = (type) =>
        JsToolbar.setEndButtonImage(type)

    EventiciousSDK.Buttons.Left.setText = (text) =>
        JsToolbar.setStartButtonText(text)

    EventiciousSDK.Buttons.Right.setText = (text) =>
        JsToolbar.setEndButtonText(text)

    EventiciousSDK.Buttons.Left.onClick = (action) => {
        if (action != null) {
            JsToolbar.setStartButtonAction(saveCallback(action));
        } else {
            JsToolbar.setStartButtonAction(null);
        }
    }

    EventiciousSDK.Buttons.Right.onClick = (action) => {
        if (action != null) {
            JsToolbar.setEndButtonAction(saveCallback(action));
        } else {
            JsToolbar.setEndButtonAction(null);
        }
    }

    // Deprecated
    EventiciousSDK.sdkVersion = () =>
        JsDataProvider.getSdkVersion()

    // Deprecated but required by Survey WebView
    EventiciousSDK.getProfile = function (eventId) {
        return this.profilesManager.getProfile(eventId)
    }

    // Deprecated
    EventiciousSDK.getUserInfo = function (eventId) {
        return null;
    }

    // Deprecated
    EventiciousSDK.updateUserInfo_v2 = function (eventId, properties) {
        return false;
    }

    // Deprecated
    EventiciousSDK.getUserInfo_v2 = function (eventId) {
        return null;
    }

    // Deprecated
    EventiciousSDK.uploadProfilePhoto = function (eventId, pin) {
        return false;
    }

    // Deprecated
    EventiciousSDK.authorize_v2 = function (username, eventId, properties) {
    }

    // Deprecated
    EventiciousSDK.authorizeWithPin_v2 = function (username, pin, eventId, properties) {
    }

    // Deprecated
    EventiciousSDK.logoutAsync = function (callback, eventId) {
        callback({ 'success': false });
    }

    // Deprecated
    EventiciousSDK.silentLogout = function (callback, eventId) {
        callback({ 'success': false });
    }

    // Deprecated
    EventiciousSDK.showRegisteredToast = function () {
    }

    // Deprecated
    EventiciousSDK.uploadPhoto = function (eventId, pin, url, quality) {
        return null
    }

    // Deprecated
    EventiciousSDK.canOpenContact = function (eventId, contactId) {
        return true;
    }

    // Deprecated
    EventiciousSDK.openContact = function (eventId, contactId) {
        JsNavigator.open("eventicious:///attendee/" + contactId, null);
    }

    // Deprecated
    EventiciousSDK.openInBrowser = function (url) {
        var properties = new Object();
        properties.external = true;
        JsNavigator.open(url, JSON.stringify(properties));
    }

    // Deprecated
    EventiciousSDK.openInWebview = function (url) {
        JsNavigator.open(url, null);
    }

    // Deprecated
    EventiciousSDK.openInWebviewWithTitle = function (url, title) {
        var properties = new Object();
        properties.title = title;
        JsNavigator.open(url, JSON.stringify(properties));
    }

    // Deprecated
    EventiciousSDK.openInModalWebview = function (url) {
        JsNavigator.open(url, null);
    }

    // Deprecated
    EventiciousSDK.toMainScreen = function () {
        JsNavigator.close();
    }

    // Deprecated
    EventiciousSDK.toLastScreen = function () {
        JsNavigator.close();
    }

    function loadingComplete() {
        window.EventiciousSDK = EventiciousSDK;
        window.EventiciousCallbacks = {
            OnLeftButtonClicked: null,
            OnRightButtonClicked: null
        }
        if (typeof onEventiciousSDKLoaded == 'function') {
            onEventiciousSDKLoaded();
        }
        window.dispatchEvent(new CustomEvent("EventiciousSDKLoaded"), {
            bubbles: true,
            composed: true,
        });

        console.log("EventiciousSDK initialized (v1.1)");
    }

    loadingComplete();
})();
