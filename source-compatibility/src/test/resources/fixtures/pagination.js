var pages = [1, 2].map(function(page) {
    return java.ajax("https://reader.invalid/chapter/1?page=" + page);
});
return {content: pages.join("\n"), events: events};
