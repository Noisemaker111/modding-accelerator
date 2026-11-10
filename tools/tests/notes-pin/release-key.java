com.chaosthedude.notes.event.NotesEvents.OPEN_NOTES.setDown(false);
String seen = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
mc.setScreen(null);
return seen;
