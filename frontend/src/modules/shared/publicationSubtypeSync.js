const SUBTYPE_MAPPING = {
  Article: 'ar',
  Book: 'bk',
  'Book Chapter': 'ch',
  'Conference Paper': 'cp',
  'Data Paper': 'dp',
  Editorial: 'ed',
  Erratum: 'er',
  Letter: 'le',
  Note: 'no',
  Review: 're',
  'Short Survey': 'sh'
};

export function initPublicationSubtypeSync() {
  const subtypeDescription = document.getElementById('subTypeDescription');
  const subtypeField = document.getElementById('subtype');

  if (!subtypeDescription || !subtypeField) {
    return;
  }

  const syncSubtype = () => {
    const resolvedSubtype = SUBTYPE_MAPPING[subtypeDescription.value];
    subtypeField.value = resolvedSubtype || 'undefined';
  };

  subtypeDescription.addEventListener('change', syncSubtype);
  syncSubtype();
}
