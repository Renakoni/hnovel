// A synthetic host double, not the Legado Java bridge. No network or disk I/O.
var events = [];
var values = {};
var loginInfo = {};
var java = {
    ajax: function(url) {
        if (!Object.prototype.hasOwnProperty.call(fixtureHost.responses, url)) throw new Error("Unrecorded fixture request");
        events.push(["request", url]);
        return fixtureHost.responses[url];
    }
};
var source = {
    getKey: function() { return fixtureHost.sourceId; },
    getVariable: function() { return fixtureHost.variable; },
    putLoginInfo: function(info) {
        loginInfo = JSON.parse(info);
        events.push(["login", loginInfo.name]);
        return true;
    },
    getLoginInfoMap: function() { return loginInfo; }
};
var cache = {
    put: function(key, value) { values[key] = value; events.push(["cache", key]); },
    get: function(key) { return values[key]; }
};
