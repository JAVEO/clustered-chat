$ ->
  form = $(".button-form")
  if form
    if $(".button-form-link")
      $(".button-form-link").click ->
        form.submit()

  window.setTimeout (->
    $(".alert").fadeTo(1500, 0).slideUp 500, ->
      $(this).remove()
  ), 2000
