// Saves a note through Notes' own Note.save(), finds it again in Note.getCurrentNotes() (read back from the notes folder)
// and pins that copy, as its Pin button does (Notes.pinnedNote).
String title = "modaccel pin check";
String text = "Gutters cleaned at 12 Elm St " + (System.nanoTime() % 100000);
for (var old : com.chaosthedude.notes.note.Note.getCurrentNotes()) if (old.getTitle().startsWith(title)) old.delete();
new com.chaosthedude.notes.note.Note(title, text, com.chaosthedude.notes.note.Scope.GLOBAL).save();
com.chaosthedude.notes.note.Note found = null;
java.util.List<String> titles = new java.util.ArrayList<>();
for (var note : com.chaosthedude.notes.note.Note.getCurrentNotes()) { titles.add(note.getTitle()); if (note.getTitle().equals(title)) found = note; }
if (found == null) throw new AssertionError("Notes' effects are missing: the saved note is not among the notes it reads back " + titles + ".");
if (!found.getRawText().strip().equals(text)) throw new AssertionError("Notes' effects are missing: the note read back says \"" + found.getRawText().strip() + "\", saved \"" + text + "\".");
com.chaosthedude.notes.Notes.pinnedNote = found;
vars.put("noteText", text);
return found.getSaveFile().getName() + ": " + text;
