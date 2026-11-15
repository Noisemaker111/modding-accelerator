// Stops the integrated server writing chunks for the rest of the launch, so the save every launch
// reads (the map's import from the save, and the bake-digest gate) stays the copy the experiment made.
var server = mc.getSingleplayerServer();
server.executeBlocking(() -> {
    for (var level : server.getAllLevels()) level.noSave = true;
});
return "save-off";
