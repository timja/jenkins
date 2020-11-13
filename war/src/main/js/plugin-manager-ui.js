import pluginManagerAvailable from './templates/plugin-manager/available.hbs'

var debounce = null;
function applyFilter(searchQuery) {
    clearTimeout(debounce);
    // debounce reduces number of server side calls while typing
    debounce = setTimeout(function() {
        view.availablePlugins(searchQuery.toLowerCase().trim(), 50, function (pluginsObj) {
            var plugins = JSON.parse(pluginsObj.responseObject());
            document.getElementById('plugins').innerHTML = pluginManagerAvailable({
                plugins
            });
        })
    }, 150);
}

document.addEventListener("DOMContentLoaded", function () {
    var filterInput = document.getElementById('filter-box');
    filterInput.addEventListener('input', function(e) {
        applyFilter(e.target.value)
    });

    applyFilter(filterInput.value);
});
