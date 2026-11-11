-- Preserve native candidate boundaries; a prefix candidate must not consume the
-- rest of a sentence. The C ABI omits end/preedit, so expose immutable metadata
-- in comments using a delimiter absent from dictionary spellings.
return function(input, env)
  for cand in input:iter() do
    local sep = string.char(31)
    local metadata = cand.comment .. sep .. tostring(cand._end) .. sep
      .. tostring(cand.quality) .. sep .. (cand.preedit or "") .. sep .. cand.type
    yield(ShadowCandidate(cand, cand.type, cand.text, metadata))
  end
end
