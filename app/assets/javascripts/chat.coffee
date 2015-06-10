msgform = -> $("#msgform")
comment = -> $("#comment")
confirmButton = -> $("#sendMessageButton")
conversation = -> $("#conversation #messages")
messages = -> $("#messages")
messageOnLeftTemplate = -> $("#message-on-left-template")

messageExists = ->
  comment().val().trim().length

changeConfirmButtonState = (disable) ->
  confirmButton().prop "disabled", disable

enableConfirmButton = ->
  changeConfirmButtonState(false)

disableConfirmButton = ->
  changeConfirmButtonState(true)

resetForm = ->
  comment().val("")

niceScrolls = ->
  conversation().niceScroll
    background: "#eee"
    cursorcolor: "#ddd"
    cursorwidth: "10px"
    autohidemode: false
    horizrailenabled: false

init = ->
  disableConfirmButton()
  niceScrolls()

$ ->
  init()

  templateScript =
    messageOnLeft: messageOnLeftTemplate().html()

  template =
    messageOnLeft: Handlebars.compile(templateScript.messageOnLeft)

  ws = new WebSocket $("body").data("ws-url")
  ws.onmessage = (event) ->
    message = JSON.parse event.data
    switch message.type
      when "message"
        messages().append(messageOnLeft(message.user, message.text))
        messages().scrollTop(messages().prop("scrollHeight"))
      else
        console.log(message)

  ws.onerror = (event) ->
    console.log "WS error: " + event

  ws.onclose = (event) ->
    console.log "WS closed: " + event.code + ": " + event.reason + " " + event

  window.onbeforeunload = ->
    ws.onclose = ->
    ws.close()

  msgform().submit (event) ->
    event.preventDefault()
    message = { msg: comment().val() }
    if messageExists()
      ws.send(JSON.stringify(message))
      resetForm()
      disableConfirmButton()

  messageOnLeft = (u, m) ->
    template.messageOnLeft(message(u, m))

  message = (user, message) ->
    user : user,
    message : message

  key_enter = 13

  comment().keyup (event) ->
    if messageExists()
      enableConfirmButton()
    else
      disableConfirmButton()
    if event.which is key_enter && !event.shiftKey
      event.preventDefault()
      if messageExists()
        msgform().submit()
