// Opens Diorama's full map screen on the render thread, as a player pressing the map key does, for
// experiment-screen.json: the screen stays open through both captures (it doesn't pause the game).
mc.setScreen(new dev.diorama.MapScreen());
return mc.screen instanceof dev.diorama.MapScreen ? "map" : "no map screen";
