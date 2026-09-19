function login() {
    var result = JSON.parse(java.ajax("https://reader.invalid/login"));
    if (!result.ok) throw new Error("Login rejected");
    source.putLoginInfo(JSON.stringify({name: result.name}));
    cache.put("logged-in", true);
}
login();
return {name: source.getLoginInfoMap().name, cached: cache.get("logged-in"), events: events};
