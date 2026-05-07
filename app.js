const STORAGE_KEY = "simple-notes-app.notes";

const noteForm = document.querySelector("#note-form");
const titleInput = document.querySelector("#note-title");
const bodyInput = document.querySelector("#note-body");
const submitButton = document.querySelector("#submit-note");
const cancelEditButton = document.querySelector("#cancel-edit");
const searchInput = document.querySelector("#search-notes");
const notesList = document.querySelector("#notes-list");
const emptyState = document.querySelector("#empty-state");
const noteCount = document.querySelector("#note-count");
const noteTemplate = document.querySelector("#note-template");

let notes = loadNotes();
let editingNoteId = null;

noteForm.addEventListener("submit", (event) => {
  event.preventDefault();

  const title = titleInput.value.trim();
  const body = bodyInput.value.trim();

  if (!title || !body) {
    return;
  }

  if (editingNoteId) {
    notes = notes.map((note) =>
      note.id === editingNoteId
        ? { ...note, title, body, updatedAt: new Date().toISOString() }
        : note,
    );
  } else {
    notes = [createNote(title, body), ...notes];
  }

  saveNotes();
  resetForm();
  renderNotes();
});

cancelEditButton.addEventListener("click", resetForm);
searchInput.addEventListener("input", renderNotes);

function createNote(title, body) {
  const now = new Date().toISOString();

  return {
    id: generateId(),
    title,
    body,
    createdAt: now,
    updatedAt: now,
  };
}

function generateId() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID();
  }

  return `note-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function loadNotes() {
  try {
    const savedNotes = localStorage.getItem(STORAGE_KEY);
    return savedNotes ? JSON.parse(savedNotes) : [];
  } catch (error) {
    console.warn("Unable to load notes from localStorage.", error);
    return [];
  }
}

function saveNotes() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(notes));
}

function renderNotes() {
  const query = searchInput.value.trim().toLowerCase();
  const matchingNotes = notes.filter((note) => {
    const searchableText = `${note.title} ${note.body}`.toLowerCase();
    return searchableText.includes(query);
  });

  notesList.innerHTML = "";
  matchingNotes.forEach((note) => notesList.appendChild(createNoteCard(note)));

  noteCount.textContent = `${notes.length} ${notes.length === 1 ? "note" : "notes"}`;
  emptyState.textContent = query
    ? "No notes match your search."
    : "No notes yet. Add your first note to get started.";
  emptyState.classList.toggle("hidden", matchingNotes.length > 0);
}

function createNoteCard(note) {
  const noteCard = noteTemplate.content.firstElementChild.cloneNode(true);
  const title = noteCard.querySelector("h3");
  const body = noteCard.querySelector("p");
  const timestamp = noteCard.querySelector("time");
  const editButton = noteCard.querySelector(".edit-note");
  const deleteButton = noteCard.querySelector(".delete-note");

  title.textContent = note.title;
  body.textContent = note.body;
  timestamp.textContent = formatDate(note.updatedAt);
  timestamp.dateTime = note.updatedAt;

  editButton.addEventListener("click", () => startEditing(note));
  deleteButton.addEventListener("click", () => deleteNote(note.id));

  return noteCard;
}

function startEditing(note) {
  editingNoteId = note.id;
  titleInput.value = note.title;
  bodyInput.value = note.body;
  submitButton.textContent = "Save changes";
  cancelEditButton.classList.remove("hidden");
  titleInput.focus();
}

function deleteNote(noteId) {
  const noteToDelete = notes.find((note) => note.id === noteId);

  if (!noteToDelete || !confirm(`Delete "${noteToDelete.title}"?`)) {
    return;
  }

  notes = notes.filter((note) => note.id !== noteId);

  if (editingNoteId === noteId) {
    resetForm();
  }

  saveNotes();
  renderNotes();
}

function resetForm() {
  editingNoteId = null;
  noteForm.reset();
  submitButton.textContent = "Add note";
  cancelEditButton.classList.add("hidden");
}

function formatDate(dateString) {
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(dateString));
}

renderNotes();
