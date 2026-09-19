return JSON.parse(source.getVariable()).map(function(category) {
    return {title: category.title, url: "https://reader.invalid" + category.url};
});
