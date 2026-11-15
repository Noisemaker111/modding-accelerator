// Leaves no note or pin behind for the next run.
com.chaosthedude.notes.Notes.pinnedNote = null;
int deleted = 0;
for (var note : com.chaosthedude.notes.note.Note.getCurrentNotes()) if (note.getTitle().startsWith("mmt pin check")) { note.delete(); deleted++; }
return "deleted " + deleted;
